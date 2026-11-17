"""Issue #38 리뷰 대응: EXPLAIN 요청에서도 모델이 OUT_OF_SCOPE로 끝내면 범위 밖 보정이 우선한다(M-5, AC-1)."""

import json
from pathlib import Path

from app.core.copy import load_copy
from app.llm.agent import ChatAgent
from app.schemas import ChatRequest
from tests.test_out_of_scope import MODEL_OWN_SENTENCE, FakeCalc, FakeClaude, FakeRetriever, text_resp

PLAN_CONTEXT = json.loads(   # 계약 C1a 예시 planContext (calcRunId 540)
    (Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json")
    .read_text(encoding="utf-8")
)


def explain_request() -> ChatRequest:
    return ChatRequest(
        memberId=1, messageId=9, mode="EXPLAIN", calcRunId=540, baselineScenario="AVG", planContext=PLAN_CONTEXT
    )


def make_agent(claude: FakeClaude) -> ChatAgent:
    return ChatAgent(claude, FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


async def test_out_of_scope_in_explain_mode_drops_calc_run_and_uses_copy_book():
    resp = await make_agent(FakeClaude([text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE)])).run(explain_request())
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert resp.content.startswith(load_copy().get("outOfScope"))
    assert MODEL_OWN_SENTENCE not in resp.content


async def test_explain_mode_without_out_of_scope_still_returns_explanation_with_request_calc_run():
    resp = await make_agent(FakeClaude([text_resp("ANSWER", "설명이에요")])).run(explain_request())
    assert resp.message_type == "EXPLANATION"
    assert resp.calc_run_id == 540
    assert resp.content == "설명이에요"
