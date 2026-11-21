"""Issue #190: 고정 문구(목표 종류·보유 자산 변경 안내, 계획 없음 안내)는 모델이 덧붙이거나 바꿔도 서버가 문구표 문장으로 돌려놓는다.

실제 LLM 점검에서 모델이 "목표 금액·종목·기존 납입액이나 보유 자산은 대화로 바꿀 수 없어요"처럼 문구에 없는 문장을 덧붙였다.
목표 금액은 대화로 바꿀 수 있어(D-090) 사용자를 오도한다. 범위 밖 세제 문구(outOfScope.tax)를 서버가 그대로 두는 것과 같은 방식이다.
"""

import pytest

from app.core.copy import load_copy
from app.llm.agent import ChatAgent
from tests.test_out_of_scope import (
    PLAN_CONTEXT,
    FakeCalc,
    FakeClaude,
    FakeRetriever,
    MarkerJudge,
    question,
    text_resp,
)

COPY = load_copy()
UNCHANGEABLE = COPY.get("chat.unchangeableCondition")
NO_PLAN = COPY.get("chat.noPlan")


def agent(claude: FakeClaude) -> ChatAgent:
    return ChatAgent(claude, FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4,
                     stock_judge=MarkerJudge())


@pytest.mark.parametrize(
    "model_text",
    [
        f"보유 자산은 여기서 바꿀 수 없어요. {UNCHANGEABLE}",
        "목표 금액·종목·기존 납입액이나 보유 자산은 대화로 바꿀 수 없어요. 보유 자산은 입력 수정에서 바꿀 수 있어요.",
        "그건 어려워요. 보유 자산은 입력 수정에서 바꿀 수 있어요",
    ],
)
async def test_unchangeable_condition_answer_is_replaced_by_the_copy_sentence(model_text):
    """AC: 모델이 문구에 없는 문장을 덧붙여도 본문은 문구표 문장 그대로다."""
    claude = FakeClaude([text_resp("ANSWER", model_text)])

    resp = await agent(claude).run(question("보유 자산을 1억으로 바꿔줘", planContext=PLAN_CONTEXT))

    assert resp.content == UNCHANGEABLE
    assert resp.message_type == "ANSWER"
    assert len(claude.calls) == 1


@pytest.mark.parametrize("model_text", [f"{NO_PLAN}.", f"{NO_PLAN}요!", f"죄송해요. {NO_PLAN}"])
async def test_no_plan_answer_is_replaced_by_the_copy_sentence(model_text):
    """AC: 계획이 없을 때 모델이 마침표·말을 덧붙여도 본문은 문구표 문장 그대로다."""
    claude = FakeClaude([text_resp("ANSWER", model_text)])

    resp = await agent(claude).run(question("내 계획 결과가 어때?"))

    assert resp.content == NO_PLAN


async def test_no_plan_copy_is_left_alone_when_a_plan_exists():
    """(예외) 계획이 있으면 이 문구를 강제하지 않는다. 모델 문장 그대로 나간다."""
    claude = FakeClaude([text_resp("ANSWER", f"참고로 {NO_PLAN}")])

    resp = await agent(claude).run(question("설명해 줘", planContext=PLAN_CONTEXT))

    assert resp.content == f"참고로 {NO_PLAN}"


async def test_ordinary_answer_is_not_touched():
    claude = FakeClaude([text_resp("ANSWER", "ETF는 여러 자산을 묶은 펀드예요.")])

    resp = await agent(claude).run(question("ETF가 뭐야?", planContext=PLAN_CONTEXT))

    assert resp.content == "ETF는 여러 자산을 묶은 펀드예요."


async def test_out_of_scope_answer_keeps_its_own_server_copy():
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", "보유 자산은 입력 수정에서 바꿀 수 있어요")])

    resp = await agent(claude).run(question("삼성전자 사도 돼?", planContext=PLAN_CONTEXT))

    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.content != UNCHANGEABLE
