"""Issue #57: 본문에 display 키 이름(diff.AVG.afterTaxAssetDelta 등)이 그대로 나가지 않게 한다(NFR-USA-01).

실제 LLM 시나리오 점검에서 모델이 "diff. 키 문자열을 그대로 인용"하라는 지시를 키 이름을 쓰라는 뜻으로 읽어
키 이름을 사용자에게 보여 줬다. 개별 종목 추천과 같은 방식으로 1회 재시도하고 또 어기면 거부한다.
"""

import pytest

from app.llm.agent import AgentError, ChatAgent
from app.schemas import ChatResponse
from tests.test_out_of_scope import (
    PLAN_CONTEXT,
    FakeCalc,
    FakeClaude,
    FakeRetriever,
    MarkerJudge,
    question,
    text_resp,
)

LEAKED = "평균 시나리오 차이는 diff.AVG.afterTaxAssetDelta 기준으로 말씀드릴게요."
CLEAN = "평균 시나리오 기준으로 차이를 말씀드릴게요."


def agent(claude: FakeClaude) -> ChatAgent:
    return ChatAgent(claude, FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4,
                     stock_judge=MarkerJudge())


@pytest.mark.parametrize(
    "key_text",
    ["diff.AVG.afterTaxAssetDelta", "current.AVG.afterTaxAsset", "changed.GOOD.goalReachMonth",
     "conditions.changed.monthlySavingAmount", "AVG.gapToGoal", "assumptions.taxRatePct"],
)
async def test_display_key_in_body_is_retried_once_and_clean_retry_goes_out(key_text):
    """AC: 본문에 표시 키 이름이 있으면 모델을 한 번 다시 부르고, 깨끗한 재시도 답이 나간다."""
    claude = FakeClaude([text_resp("ANSWER", f"차이는 {key_text}예요."), text_resp("ANSWER", CLEAN)])

    resp = await agent(claude).run(question("그래서 얼마나 달라져?", planContext=PLAN_CONTEXT))

    assert isinstance(resp, ChatResponse)
    assert resp.content == CLEAN
    assert len(claude.calls) == 2
    assert "키" in str(claude.calls[1]["messages"][-1]["content"])   # 교정 지시가 키 이름 사용을 지적한다


async def test_display_key_in_both_answers_is_rejected():
    """(예외) 재시도 답도 키 이름을 쓰면 거부한다(Spring이 FAILED로 기록)."""
    claude = FakeClaude([text_resp("ANSWER", LEAKED), text_resp("ANSWER", LEAKED)])

    with pytest.raises(AgentError) as e:
        await agent(claude).run(question("그래서 얼마나 달라져?", planContext=PLAN_CONTEXT))

    assert e.value.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2


@pytest.mark.parametrize(
    "body",
    ["평균 시나리오(AVG)는 목표에 못 미쳐요.", "ETF 차익 세율은 15.4%로 가정했어요.", "S&P500 추종 ETF예요.",
     "목표는 약 2,000만원이에요."],
)
async def test_ordinary_body_is_not_mistaken_for_a_key(body):
    """정상 본문(시나리오 약어, 소수점 세율, 지수 이름, 금액)은 막지 않는다. 숫자는 계획 표시값에 있는 것만 쓴다."""
    claude = FakeClaude([text_resp("ANSWER", body)])

    resp = await agent(claude).run(question("설명해 줘", planContext=PLAN_CONTEXT))

    assert resp.content.startswith(body)
    assert len(claude.calls) == 1
