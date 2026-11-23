"""Issue #233 수용 테스트: 세금 단서(tax.note·tax.noteEtfTaxFree)는 답에 붙이지 않고 대화 패널이 고정 문구로 보여 준다.

옛 허브 테스트 파일을 import하지 않고 `docs/api/examples/plan-context/`의 planContext 예시만 읽는다.
"""

import json
from typing import Any

import pytest

from app.core.copy import load_copy
from app.llm.assemble import without_tax_notes
from app.schemas import ChatRequest
from tests.paths import REPO

EXAMPLES = REPO / "docs" / "api" / "examples" / "plan-context"
COPY = load_copy()
TAX_NOTE = COPY.get("tax.note")
TAX_NOTE_FREE = COPY.get("tax.noteEtfTaxFree")
PLANS = ["plan-context-basic.json", "plan-context-etf0.json", "plan-context-etf-tax-free.json"]


def request(plan_name: str) -> ChatRequest:
    plan: dict[str, Any] = json.loads((EXAMPLES / plan_name).read_text(encoding="utf-8"))
    return ChatRequest.model_validate({
        "memberId": 1, "messageId": 1, "mode": "EXPLAIN", "text": None, "baselineScenario": "AVG",
        "calcRunId": plan["calcRunId"], "planContext": plan, "history": [],
    })


def has_no_note(content: str) -> bool:
    return TAX_NOTE not in content and TAX_NOTE_FREE not in content


@pytest.mark.parametrize("plan_name", PLANS)
def test_a_answer_quoting_the_tax_rate_gets_no_note(plan_name: str):
    req = request(plan_name)
    assert req.plan_context is not None
    answer = f"적금 이자 세율은 {req.plan_context.display['assumptions.taxRatePct']}로 가정했어요."

    content = without_tax_notes(req, answer)

    assert content == answer and has_no_note(content)


@pytest.mark.parametrize("plan_name", PLANS)
@pytest.mark.parametrize("newline", [False, True], ids=["space", "newline"])
def test_b_both_notes_written_by_the_model_are_removed(plan_name: str, newline: bool):
    body = "적금 이자 세율은 15.4%로 가정했어요."
    notes = [TAX_NOTE, TAX_NOTE_FREE]
    if newline:   # 문장 사이 공백이 줄바꿈이어도 같은 단서다
        notes = [note.replace(" ", "\n") for note in notes]

    content = without_tax_notes(request(plan_name), f"{body}\n\n{notes[0]}\n\n{notes[1]}")

    assert content == body and has_no_note(content)


@pytest.mark.parametrize("note", [TAX_NOTE, TAX_NOTE_FREE], ids=["general", "tax-free"])
def test_c_rate_joined_by_removal_is_spaced(note: str):
    """P-6(#155): 단서를 지우자 양옆이 이어져 세율 표시값이 새로 생기면 지운 자리에 한 칸을 띄운다."""
    content = without_tax_notes(request("plan-context-basic.json"), f"세율은 15.4{note}%예요.")

    assert content == "세율은 15.4 %예요." and has_no_note(content)
    assert "15.4%" not in content
