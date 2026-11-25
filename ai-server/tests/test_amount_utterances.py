"""#231 금액 발화 표. 사용자가 실제로 말할 법한 금액 표현을 모아 후보 금액·단위 대조·이어받은 값 필터를 함께 검사한다."""

import pytest

from app.llm.conditions import _candidate_krw_amounts, check_amount_units, drop_carried_conditions
from app.schemas import ConditionChanges

ONE_AND_HALF_EOK = 150_000_000

# (발화, 들어 있어야 하는 금액)
CANDIDATES = [
    ("1억5천만원", ONE_AND_HALF_EOK),
    ("1억 5천만원", ONE_AND_HALF_EOK),
    ("목표금액 1억5천만원", ONE_AND_HALF_EOK),
    ("목표 금액을 1억 5천만 원으로", ONE_AND_HALF_EOK),
    ("1억 5000만원", ONE_AND_HALF_EOK),
    ("15000만원", ONE_AND_HALF_EOK),
    ("15,000만원", ONE_AND_HALF_EOK),
    ("1.5억", ONE_AND_HALF_EOK),
    ("일억 오천만원", ONE_AND_HALF_EOK),
    ("5천만원", 50_000_000),
    ("1천5백만원", 15_000_000),
    ("목표금액 1억으로", 100_000_000),
    ("2억", 200_000_000),
    ("월 50만원", 500_000),
]


@pytest.mark.parametrize(("text", "amount"), CANDIDATES, ids=[c[0] for c in CANDIDATES])
def test_candidate_amounts_include_spoken_value(text: str, amount: int) -> None:
    assert amount in _candidate_krw_amounts(text.lower())


@pytest.mark.parametrize(
    "text",
    ["1억5천만원", "1억 5천만원", "목표금액 1억5천만원", "15000만원", "그럼 15,000만원까지 하려면 얼마나걸려"],
)
def test_goal_amount_unit_check_passes(text: str) -> None:
    assert check_amount_units(text, ConditionChanges(goal_amount=ONE_AND_HALF_EOK))


def test_goal_amount_unit_check_still_rejects_wrong_value() -> None:
    assert not check_amount_units("목표금액 1억으로", ConditionChanges(goal_amount=ONE_AND_HALF_EOK))


@pytest.mark.parametrize(
    "text",
    ["15000만원", "1억5천만원", "그럼 15,000만원까지 하려면 얼마나걸려", "목표 금액 15000만원"],
)
def test_amount_said_now_is_not_dropped_as_carried(text: str) -> None:
    changes = ConditionChanges(goal_amount=ONE_AND_HALF_EOK)
    assert drop_carried_conditions(text, changes).goal_amount == ONE_AND_HALF_EOK


def test_amount_from_earlier_turn_is_still_dropped() -> None:
    # 이번 발화는 기간만 말했다. 앞 턴의 목표 금액 1.5억을 모델이 다시 넣으면 뺀다
    changes = ConditionChanges(goal_amount=ONE_AND_HALF_EOK, target_period_months=48)
    filtered = drop_carried_conditions("기간은 4년으로", changes)
    assert filtered.goal_amount is None
    assert filtered.target_period_months == 48
