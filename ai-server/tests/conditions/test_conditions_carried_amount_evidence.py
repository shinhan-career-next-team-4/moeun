"""Issue #260: 직전 사용자 발화의 금액을 이어받아 계산했으면, 답이 그 발화의 금액 표현을 써도 숫자 검사를 통과한다.

#240으로 역할 단어 없는 후속 발화("목표 금액")가 직전 발화("1억으로 바꿀게")의 값으로 계산하게 됐지만, 숫자 검사 근거는
이번 발화·계획 display·계산 display뿐이라 답의 "1억"이 NUMBER_MISMATCH가 됐다(e2e S8-8b). 계산에 실제로 쓴 금액이 나온
발화만 근거에 더하고, 이전 발화 전체를 근거로 열지 않는다.
"""

import json
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.core.errors import ErrorCode
from app.llm.agent import ChatAgent
from app.llm.conditions import carried_amount_sources
from app.llm.errors import AgentError
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk
from tests.fakes import FakeCalc

NULLS = dict.fromkeys(
    ["goal_amount", "monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"]
)
ASKED_ROLE = [
    {"role": "user", "content": "1억으로 바꿀게"},
    {"role": "assistant", "content": "1억으로 바꾸고 싶은 조건이 목표 금액, 월 저축액 중 어느 것인지 알려 주시겠어요?"},
]


def text_resp(message_type: str, content: str) -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(input_: dict[str, Any]) -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id="tu_1", name="recalculate", input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[SimpleNamespace]):
        self.responses = list(responses)
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> SimpleNamespace:
        return self.responses.pop(0)


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


async def run(history: list[dict[str, str]], text: str, amount: int, answer: str) -> str:
    claude = FakeClaude([tool_resp({**NULLS, "goal_amount": amount}), text_resp("RECALC_RESULT", answer)])
    agent = ChatAgent(claude, FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", history=history)
    return (await agent.run(req)).content


# ---- 수용 기준 1 ----

async def test_answer_may_quote_the_carried_amount_in_the_users_words():
    content = await run(ASKED_ROLE, "목표 금액", 100_000_000, "목표 금액을 1억으로 바꿔 계산했어요.")
    assert "1억" in content


# ---- 수용 기준 2·3: 근거를 넓게 열지 않는다 ----

async def test_amount_from_an_older_utterance_not_used_in_this_calc_is_still_rejected():
    history = [
        {"role": "user", "content": "목표 금액을 2억으로 하면요?"},
        {"role": "assistant", "content": "목표 금액을 바꿔 계산했어요."},
        *ASKED_ROLE,
    ]
    with pytest.raises(AgentError) as error:
        await run(history, "목표 금액", 100_000_000, "목표 금액을 2억에서 1억으로 바꿔 계산했어요.")
    assert error.value.code == ErrorCode.NUMBER_MISMATCH


async def test_history_is_not_evidence_when_this_utterance_states_the_amount():
    history = [{"role": "user", "content": "목표 금액을 2억으로 하면요?"}, {"role": "assistant", "content": "계산했어요."}]
    with pytest.raises(AgentError) as error:
        await run(history, "목표 금액 1억 2천만원으로", 120_000_000, "2억에서 1억 2천만원으로 바꿔 계산했어요.")
    assert error.value.code == ErrorCode.NUMBER_MISMATCH


# ---- 근거를 고르는 함수 ----

@pytest.mark.parametrize(
    ("history_texts", "text", "changes", "expected"),
    [
        pytest.param(["1억으로 바꿀게"], "목표 금액", {"goal_amount": 100_000_000}, ["1억으로 바꿀게"], id="carried"),
        pytest.param(["2억으로", "1억으로 바꿀게"], "목표 금액", {"goal_amount": 100_000_000}, ["1억으로 바꿀게"],
                     id="only_the_source_utterance"),
        pytest.param(["1억으로 바꿀게"], "목표 금액 1억으로", {"goal_amount": 100_000_000}, [], id="stated_now"),
        pytest.param(["기간을 4년으로"], "목표 금액", {"goal_amount": 100_000_000}, [], id="no_source"),
        pytest.param(["4년으로"], "그걸로", {"target_period_months": 48}, [], id="not_money"),
    ],
)
def test_carried_amount_sources(history_texts, text, changes, expected):
    assert carried_amount_sources(text, history_texts, ConditionChanges.model_validate(changes)) == expected
