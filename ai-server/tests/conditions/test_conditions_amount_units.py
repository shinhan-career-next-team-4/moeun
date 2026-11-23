"""Issue #236: 억 뒤에서 만을 생략한 금액(1억 2천)과 숫자+천·백(5천만원)을 단위 대조에서 인식한다.

단위 대조(check_amount_units, CF12-9-3)는 모델이 넣은 목표 금액이 발화의 숫자·단위 표현에서 나올 수 있는 값인지 본다.
후보를 못 만들면 맞는 값도 오환산으로 걸려 같은 되묻기가 반복된다.
"""

import json
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.llm.agent import ChatAgent
from app.llm.conditions import check_amount_units
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk
from tests.fakes import FakeCalc


def goal(amount: int) -> ConditionChanges:
    return ConditionChanges.model_validate({"goal_amount": amount})


# ---- 수용 기준 1·2: 만 생략·숫자+천·백 표현을 금액으로 읽는다 ----

@pytest.mark.parametrize(
    ("text", "amount"),
    [
        pytest.param("목표금액 1억2천으로", 120_000_000, id="eok_cheon_attached"),
        pytest.param("1억 2천", 120_000_000, id="eok_space_cheon"),
        pytest.param("1억 2천만원", 120_000_000, id="eok_cheon_man_won"),
        pytest.param("1억 이천", 120_000_000, id="eok_korean_cheon"),
        pytest.param("1억 이천만원", 120_000_000, id="eok_korean_cheon_man"),
        pytest.param("목표 1억 2천만 원", 120_000_000, id="eok_cheon_man_space_won"),
        pytest.param("1억 5백", 105_000_000, id="eok_baek"),
        pytest.param("목표 5천만원", 50_000_000, id="digit_cheon_man"),
    ],
)
def test_omitted_man_and_scaled_digits_pass_unit_check(text, amount):
    """억 뒤 천·백은 천만·백만이고, 숫자+천·백+만은 그 배수의 만 원이다"""
    assert check_amount_units(text, goal(amount)) is True


# ---- 수용 기준 4·5: 오환산과 모호한 금액은 계속 막는다 (예외) ----

@pytest.mark.parametrize(
    ("text", "amount"),
    [
        pytest.param("1억 2천", 12_000_000, id="eok_cheon_as_cheonman_only"),
        pytest.param("1억 2천", 102_000_000, id="eok_cheon_as_2man"),
        pytest.param("목표 5천만원", 10_000_000, id="digit_dropped"),
        pytest.param("목표 3천으로", 30_000_000, id="bare_cheon_is_ambiguous"),
    ],
)
def test_wrong_or_ambiguous_amount_still_fails_unit_check(text, amount):
    """(예외) 발화에서 나올 수 없는 값, 만 없이 천만 붙은 금액은 단위 대조에서 걸린다"""
    assert check_amount_units(text, goal(amount)) is False


@pytest.mark.parametrize(
    ("text", "amount"),
    [
        pytest.param("목표 1억으로", 100_000_000, id="eok_only"),
        pytest.param("1억 2,000만원", 120_000_000, id="eok_comma_man"),
        pytest.param("1.2억", 120_000_000, id="decimal_eok"),
        pytest.param("2,500만원", 25_000_000, id="comma_man"),
        pytest.param("8천만", 80_000_000, id="digit_cheon_man_no_won"),
    ],
)
def test_existing_amount_forms_still_pass(text, amount):
    """(예외) 이미 읽던 형태는 그대로 읽는다"""
    assert check_amount_units(text, goal(amount)) is True


# ---- 수용 기준 3: 재현 대화의 후속 발화가 계산까지 간다 ----

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
HISTORY = [
    {"role": "user", "content": "목표금액 1억2천으로"},
    {"role": "assistant", "content": "목표 금액을 정확히 얼마로 바꾸고 싶으신지 다시 말씀해 주시겠어요?"},
]


async def test_follow_up_eok_cheon_reaches_calc():
    """되묻기 뒤 "1억 2천"에 목표 금액 1억 2천만 원으로 계산을 부르면 Spring까지 간다 (재현 대화)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "goal_amount": 120_000_000}),
        text_resp("RECALC_RESULT", "목표 금액을 바꿔 계산했어요."),
    ])
    calc = FakeCalc()
    agent = ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text="1억 2천", baselineScenario="AVG", history=HISTORY)
    resp = await agent.run(req)
    assert calc.calls == [{"goal_amount": 120_000_000}]
    assert resp.message_type == "RECALC_RESULT"
