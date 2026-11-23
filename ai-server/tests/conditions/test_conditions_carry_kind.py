"""Issue #239: 역할 단어 없는 후속 금액 발화는 직전 대화가 다룬 조건으로 계산한다.

모델이 규칙을 실제로 지키는지는 FakeClaude로 보장할 수 없어 실제 LLM 시나리오 S8-8(scripts/e2e) 몫이다(D-073).
여기서는 프롬프트 규칙의 핵심 낱말과, 그 규칙대로 부른 계산을 서버(조건 필터·단위 대조)가 막지 않는지만 본다.
"""

import json
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.llm.agent import ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, DocType, RetrievedChunk
from tests.fakes import FakeCalc


def bullet(keyword: str) -> str:
    """keyword가 들어 있는 프롬프트 불릿 한 줄"""
    bullets = [b for b in SYSTEM_PROMPT.split("\n- ") if keyword in b]
    assert len(bullets) == 1, bullets
    return bullets[0]


# ---- 수용 기준 1·3·4: 역할 규칙 ----

def test_role_rule_uses_previous_turn_condition_before_asking():
    """역할 단어가 없어도 직전 턴이 한 조건만 다뤘으면 그 조건으로 보고 되묻지 않는다"""
    rule = bullet("역할 규칙")
    assert "직전" in rule
    assert "한 조건" in rule
    assert "되묻지 않" in rule


def test_role_rule_reads_reach_amount_expression_as_goal():
    """"N까지 모으려면/하려면"은 역할 단어가 없어도 목표 금액이다(S8-3 실제 실행에서 되물음)"""
    rule = bullet("역할 규칙")
    assert "까지 모으려면" in rule
    assert "까지 하려면" in rule


def test_role_rule_still_asks_without_history_or_with_several_conditions():
    """(예외) 직전 턴이 없거나 여러 조건을 다뤘으면 지금처럼 되묻는다"""
    rule = bullet("역할 규칙")
    assert "여러 조건" in rule
    assert "CLARIFY" in rule


def test_role_question_does_not_offer_held_assets():
    """(예외) 어느 조건인지 되물을 때 대화로 바꿀 수 없는 보유 자산을 선택지로 넣지 않는다 (D-090)"""
    rule = bullet("역할 규칙")
    assert "선택지" in rule
    assert "보유 자산을 선택지로 넣지 않" in rule


# ---- 수용 기준 2·5: 조건만 고른 답과 누적 금지 ----

def test_condition_only_answer_to_role_question_uses_pending_value():
    """어느 조건인지 되물은 뒤 조건만 고르면, 되묻기 바로 앞 사용자 발화의 값으로 계산한다"""
    rule = bullet("되묻기 바로 앞")
    assert "recalculate" in rule
    assert "쌓는 것이 아닙니다" in rule


def test_no_accumulate_rule_is_kept():
    """(예외) 앞 턴에서 계산해 본 값은 여전히 이어받지 않는다 (D-041)"""
    rule = bullet("history의 앞선 턴에 나온 값은 다시 넣지 않습니다")
    assert "쌓지 않습니다" in rule


def test_condition_only_sentence_still_asks_value_unless_answering_role_question():
    """(예외) 값 없이 조건만 고르는 문장은 여전히 값을 되묻되, 어느 조건인지 되물은 답이면 예외다"""
    rule = bullet("조건 고르기")
    assert "CLARIFY" in rule
    assert "어느 조건인지" in rule


# ---- 수용 기준 6: 규칙대로 부른 계산을 서버가 막지 않는다 ----

def text_resp(message_type: str, content: str) -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(name: str, input_: dict[str, Any]) -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id="tu_1", name=name, input=input_)
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


NULLS = dict.fromkeys(
    ["goal_amount", "monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"]
)
ASKED_GOAL = [
    {"role": "user", "content": "목표금액 얼마로 해뒀지?"},
    {"role": "assistant", "content": "지금 목표 금액은 약 8,000만원이에요."},
]
ASKED_ROLE = [
    {"role": "user", "content": "1억으로 바꿀게"},
    {"role": "assistant", "content": "1억으로 바꾸고 싶은 조건이 목표 금액, 월 저축액 중 어느 것인지 알려 주시겠어요?"},
]
ASKED_VALUE = [
    {"role": "user", "content": "목표금액 1억2천으로"},
    {"role": "assistant", "content": "목표 금액을 정확히 얼마로 바꾸고 싶으신지 다시 말씀해 주시겠어요?"},
]


@pytest.mark.parametrize(
    ("history", "text", "amount"),
    [
        pytest.param(ASKED_GOAL, "1억으로 바꿀게", 100_000_000, id="after_goal_lookup"),
        pytest.param(ASKED_VALUE, "1억 2천", 120_000_000, id="after_value_question"),
        pytest.param(ASKED_ROLE, "목표금액", 100_000_000, id="condition_only_after_role_question"),
        pytest.param(ASKED_ROLE, "목표 금액이요", 100_000_000, id="condition_only_with_ending"),
        pytest.param(
            [{"role": "user", "content": "목표 금액을 1억으로 바꾸면요?"},
             {"role": "assistant", "content": "목표 금액을 1억원으로 바꿔 계산했어요."}],
            "그럼 15,000만원까지 하려면 얼마나 걸려?", 150_000_000, id="reach_amount_after_goal_recalc",
        ),
    ],
)
async def test_follow_up_without_role_word_reaches_calc(history, text, amount):
    """재현 대화의 후속 발화로 목표 금액 계산을 부르면 조건 필터·단위 대조를 지나 Spring까지 간다"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "goal_amount": amount}),
        text_resp("RECALC_RESULT", "목표 금액을 바꿔 계산했어요."),
    ])
    calc = FakeCalc()
    agent = ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", history=history)
    resp = await agent.run(req)
    assert calc.calls == [{"goal_amount": amount}]
    assert resp.message_type == "RECALC_RESULT"
