"""Issue #23(T-015): tests/fakes의 FakeCalc(부록 A-1 모양)와 프롬프트 규칙 4, 보고값 = 보낸 값.

Spring·HTTP 없이 fake Calculator만으로 돈다. FakeCalc는 이 Issue가 `tests/fakes/__init__.py`에서 내보낸다:

    FakeCalc(response: dict | None = None, error: Exception | None = None)
    .calls: list[dict]      # recalculate 호출마다 changes.changed()(snake_case)를 추가. error가 있어도 먼저 기록한다
    await .recalculate(member_id, baseline, changes) -> dict   # response 없으면 success-response.json 복사본
"""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.core.copy import load_copy
from app.llm.agent import ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk, Scenario
from app.tools.calc import CalcRejected

REPO = Path(__file__).resolve().parents[2]
CALC_EXAMPLES = REPO / "docs" / "api" / "examples" / "internal-calc"
PLAN_EXAMPLES = REPO / "docs" / "api" / "examples" / "plan-context"
NULLS = dict.fromkeys(["monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"])


def load(directory: Path, name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((directory / name).read_text(encoding="utf-8"))
    return data


def fake_calc(**kw: Any) -> Any:
    from tests.fakes import FakeCalc  # 지연 import: 없으면 이 헬퍼를 쓰는 테스트만 실패한다

    return FakeCalc(**kw)


# ---- fake ----

def text_resp(message_type: str = "RECALC_RESULT", content: str = "답") -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name="recalculate", input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[Any]):
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


def make_agent(claude: FakeClaude, calc: Any) -> ChatAgent:
    return ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text: str = "월 저축액을 50만원으로 바꾸면?", **kw: Any) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


def last_tool_result(claude: FakeClaude) -> dict[str, Any]:
    block: dict[str, Any] = claude.calls[-1]["messages"][-1]["content"][0]
    return block


# ---- AC-12(계약 반영 G-4): FakeCalc.recalculate는 부록 A-1 모양 ----

async def test_fake_calc_returns_appendix_a1_shape_not_the_old_shape():
    result = await fake_calc().recalculate(1, Scenario.AVG, ConditionChanges(monthly_saving_amount=500_000))
    assert result == load(CALC_EXAMPLES, "success-response.json")
    assert set(result) == {"calcRunId", "conditions", "current", "changed", "diff", "display"}
    assert "goodCase" not in result and "finalAmount" not in json.dumps(result)


async def test_fake_calc_records_calls_and_hands_out_independent_copies():
    calc = fake_calc()
    first = await calc.recalculate(1, Scenario.AVG, ConditionChanges(monthly_saving_amount=500_000))
    first["display"].clear()
    second = await calc.recalculate(1, Scenario.AVG, ConditionChanges(target_period_months=60))
    assert second == load(CALC_EXAMPLES, "success-response.json")
    assert calc.calls == [{"monthly_saving_amount": 500_000}, {"target_period_months": 60}]


async def test_fake_calc_can_raise_calc_rejected_and_still_records_the_call():
    rejected = CalcRejected("VALIDATION_FAILED", [{"field": "monthlySavingAmount", "reason": "OUT_OF_RANGE"}])
    calc = fake_calc(error=rejected)
    with pytest.raises(CalcRejected) as caught:
        await calc.recalculate(1, Scenario.AVG, ConditionChanges(monthly_saving_amount=1))
    assert caught.value is rejected
    assert calc.calls == [{"monthly_saving_amount": 1}]


async def test_planContext_display_and_tool_display_reach_the_model_in_the_same_turn():
    """이 fake로 'planContext display + 도구 display가 같은 턴에 들어가는' 테스트를 쓸 수 있다(G-4)."""
    plan = load(PLAN_EXAMPLES, "plan-context-basic.json")
    claude = FakeClaude([tool_resp({**NULLS, "monthly_saving_amount": 500_000}), text_resp()])
    await make_agent(claude, fake_calc()).run(question(plan_context=plan))

    first_user = claude.calls[0]["messages"][0]["content"]   # 같은 리스트가 뒤에 늘어나므로 처음 user 메시지
    assert "<plan_context>" in first_user and plan["display"]["AVG.gapToGoal"] in first_user
    tool_display = json.loads(last_tool_result(claude)["content"])["display"]
    assert tool_display["current.AVG.gapToGoal"] == "-약 288만원 미달"
    assert tool_display["diff.AVG.afterTaxAssetDelta"] == "약 789만원 증가"
    assert "AVG.gapToGoal" in plan["display"] and "AVG.gapToGoal" not in tool_display   # 두 맵의 키는 접두어로 갈린다


# ---- AC-11(계약 반영 규칙 4)·AC-2: 프롬프트 ----

def prompt_lines() -> list[str]:
    return SYSTEM_PROMPT.splitlines()


def test_prompt_rule4_says_comparison_uses_only_tool_prefixed_keys_and_not_to_mix_with_plan_context():
    # 규칙 4 한 줄(또는 이어진 한 문단 줄)에 세 접두어, plan_context, '섞지' 표현이 함께 있어야 한다.
    # 모델이 지키는지는 FakeClaude로 검증할 수 없으므로(D-073) 수동 평가 몫이다
    assert any(
        all(token in line for token in ("current.", "changed.", "diff.", "plan_context", "섞")) for line in prompt_lines()
    )


def test_prompt_tells_model_to_quote_diff_display_strings_instead_of_subtracting():
    assert any("diff." in line and "인용" in line for line in prompt_lines())


def test_prompt_tells_model_to_quote_min_max_display_on_validation_failure():
    assert "minDisplay" in SYSTEM_PROMPT and "maxDisplay" in SYSTEM_PROMPT


def test_prompt_has_fixed_fallback_sentence_for_missing_range_display():
    assert "허용 범위를 벗어났어요" in SYSTEM_PROMPT


# ---- AC-6: FakeCalc 경로의 재계산 가드(요청 안에서 VALIDATION_FAILED 뒤 호출 수 1) ----

async def test_validation_failed_then_second_recalculate_never_reaches_the_calculator():
    rejected = CalcRejected(
        "VALIDATION_FAILED",
        [{"field": "monthlySavingAmount", "reason": "OUT_OF_RANGE", "min": 10000, "max": 10000000, "unit": "KRW",
          "minDisplay": "1만원", "maxDisplay": "1,000만원"}],
    )  # fmt: skip
    calc = fake_calc(error=rejected)
    claude = FakeClaude([
        tool_resp({**NULLS, "monthly_saving_amount": 12_000_000}, "tu_1"),
        tool_resp({**NULLS, "monthly_saving_amount": 10_000_000}, "tu_2"),
        text_resp("CLARIFY", "1,000만원까지 가능해요"),
    ])
    resp = await make_agent(claude, calc).run(question())
    assert calc.calls == [{"monthly_saving_amount": 12_000_000}]
    assert resp.message_type == "CLARIFY"


# ---- AC-13(계약 반영 D-071): 초과 계획 + 4개 조건 변경 발화는 기존 고정 문장 분기 ----

async def test_exceeded_plan_with_four_condition_utterance_uses_fixed_sentence_branch():
    plan = load(PLAN_EXAMPLES, "plan-context-exceeded.json")
    four = {
        "monthly_saving_amount": 600_000, "savings_ratio_pct": 30, "savings_rate_input_pct": 4.5,
        "target_period_months": 48,
    }  # fmt: skip
    calc = fake_calc(error=CalcRejected("ALREADY_EXCEEDED", []))
    claude = FakeClaude([tool_resp(four)])
    resp = await make_agent(claude, calc).run(question("월 60만원, 적금 30%, 금리 4.5%, 4년으로 바꾸면?", plan_context=plan))

    assert len(claude.calls) == 1
    assert resp.message_type == "ANSWER"
    assert resp.content == load_copy().get("chat.alreadyExceeded")
    assert resp.condition_changes is None and resp.calc_run_id is None
    wire = resp.model_dump(by_alias=True)
    assert wire["messageType"] != "RECALC_RESULT" and wire["conditionChanges"] is None


# ---- AC-14(계약 반영 H-14, C12b-14): 보고값 = 보낸 값 (RECALC 응답) ----

@pytest.mark.parametrize(
    "args",
    [
        {"monthly_saving_amount": 500_000},
        {"savings_ratio_pct": 70},
        {"savings_rate_input_pct": 4.25},
        {"target_period_months": 120},
        {"monthly_saving_amount": 300_000, "target_period_months": 24},
        {
            "monthly_saving_amount": 600_000, "savings_ratio_pct": 30, "savings_rate_input_pct": 4.5,
            "target_period_months": 48,
        },
    ],
)  # fmt: skip
async def test_reported_condition_changes_equal_the_changes_sent_to_calculator(args):
    calc = fake_calc()
    claude = FakeClaude([tool_resp({**NULLS, **args}), text_resp("RECALC_RESULT")])
    resp = await make_agent(claude, calc).run(question("조건을 바꾸면?"))

    assert resp.message_type == "RECALC_RESULT"
    assert len(calc.calls) == 1
    assert resp.condition_changes == calc.calls[0] == args   # 키·값이 빠지거나 더해지거나 바뀌지 않는다
    assert set(resp.condition_changes) <= set(NULLS)           # 4키 안에서


async def test_reported_equals_sent_when_a_carried_value_is_dropped_before_calling_calculator():
    calc = fake_calc()
    history = [
        {"role": "user", "content": "기간을 5년으로 하면?"},
        {"role": "assistant", "content": "기간 5년이면 이렇게 돼요."},
    ]
    both = {**NULLS, "monthly_saving_amount": 600_000, "target_period_months": 60}
    claude = FakeClaude([tool_resp(both), text_resp("RECALC_RESULT")])
    resp = await make_agent(claude, calc).run(question("월 저축액을 60만원으로 바꾸면?", history=history))
    assert calc.calls == [{"monthly_saving_amount": 600_000}]
    assert resp.condition_changes == calc.calls[0]


# ---- AC-15(예외, C12b-14): recalculate 결과가 없는 턴은 보내지 않은 값을 보고하지 않는다 ----

def _no_tool_clarify() -> tuple[list[Any], Any]:
    return [text_resp("CLARIFY", "무엇을 바꿔 볼까요?")], fake_calc()


def _empty_recalc_clarify() -> tuple[list[Any], Any]:
    return [tool_resp(dict(NULLS)), text_resp("CLARIFY", "무엇을 바꿀까요?")], fake_calc()


def _validation_failed() -> tuple[list[Any], Any]:
    error = CalcRejected("VALIDATION_FAILED", [{"field": "monthlySavingAmount", "reason": "REQUIRED"}])
    return [tool_resp({**NULLS, "monthly_saving_amount": 1}), text_resp("CLARIFY")], fake_calc(error=error)


def _fixed_branch(code: str) -> Any:
    def build() -> tuple[list[Any], Any]:
        return [tool_resp({**NULLS, "monthly_saving_amount": 500_000})], fake_calc(error=CalcRejected(code, []))

    return build


def _out_of_scope_after_recalc() -> tuple[list[Any], Any]:
    return [tool_resp({**NULLS, "monthly_saving_amount": 500_000}), text_resp("OUT_OF_SCOPE", "다루지 않아요")], fake_calc()


@pytest.mark.parametrize(
    "build",
    [
        _no_tool_clarify,
        _empty_recalc_clarify,
        _validation_failed,
        _fixed_branch("FIELD_NOT_CHANGEABLE"),
        _fixed_branch("NO_PLAN"),
        _fixed_branch("ALREADY_EXCEEDED"),
        _out_of_scope_after_recalc,
    ],
    ids=["clarify", "empty-recalc", "validation-failed", "not-changeable", "no-plan", "already-exceeded", "out-of-scope"],
)
async def test_turns_without_recalc_result_report_no_condition_changes(build):
    responses, calc = build()
    resp = await make_agent(FakeClaude(responses), calc).run(question())
    assert resp.message_type != "RECALC_RESULT"
    assert not resp.condition_changes   # None 또는 빈 dict
    assert resp.calc_run_id is None
    wire = resp.model_dump(by_alias=True)
    assert not wire["conditionChanges"] and wire["calcRunId"] is None


async def test_refusal_turn_reports_no_condition_changes():
    claude = FakeClaude([SimpleNamespace(stop_reason="refusal", content=[])])
    calc = fake_calc()
    resp = await make_agent(claude, calc).run(question("이 종목 사도 돼?"))
    assert resp.message_type == "OUT_OF_SCOPE" and not resp.condition_changes and calc.calls == []
