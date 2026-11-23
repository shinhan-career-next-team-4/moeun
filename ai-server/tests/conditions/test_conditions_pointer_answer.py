"""Issue #270: 되묻기에 가리키는 말("모으는 돈이요", "그거요")로 답하면 다시 되묻지 않는다.

조건 필터(drop_carried_conditions)는 이번 발화에 조건 낱말도 금액도 없으면 모델이 넘긴 값을 모두 뺐다. 그래서
조건 고르기 답 규칙(#239)이 "목표 금액이요"처럼 낱말이 맞을 때만 계산까지 갔다. 모델이 가리키는 말을 맞게 읽는지는
실제 LLM 시나리오 S8(scripts/e2e) 몫이고(D-073), 여기서는 그 계산을 서버가 막지 않는지와 누적 금지(D-041)를 본다.
"""

import pytest

from app.llm.agent import ChatAgent
from app.llm.conditions import drop_carried_conditions
from app.schemas import ChatRequest, ConditionChanges, HistoryItem
from tests.conditions.test_conditions_carry_kind import NULLS, FakeClaude, FakeRetriever, bullet, text_resp, tool_resp
from tests.fakes import FakeCalc

EOK = 100_000_000


def history(*pairs: tuple[str, str]) -> list[HistoryItem]:
    return [HistoryItem(role=role, content=content) for role, content in pairs]  # type: ignore[arg-type]


ASKED_ROLE = history(
    ("user", "1억으로 바꿀게"),
    ("assistant", "1억으로 바꾸고 싶은 조건이 목표 금액, 월 저축액 중 어느 것인지 알려 주시겠어요?"),
)
ASKED_CONFIRM = history(
    ("user", "목표 1억으로 해 볼래"),
    ("assistant", "목표 금액을 1억으로 바꿔 계산해 볼까요?"),
)
AFTER_RECALC = history(
    ("user", "목표 금액을 1억으로 바꾸면요?"),
    ("assistant", "목표 금액을 1억원으로 바꿔 계산했어요."),
)


def goal(amount: int) -> ConditionChanges:
    return ConditionChanges.model_validate({"goal_amount": amount})


# ---- 수용 기준 1: 가리키는 답은 되묻기 바로 앞 발화의 금액을 남긴다 ----

@pytest.mark.parametrize("text", ["모으는 돈이요", "그거요", "네 그걸로요", "첫 번째요"])
def test_pointer_answer_keeps_amount_from_utterance_before_question(text):
    assert drop_carried_conditions(text, goal(EOK), ASKED_ROLE).goal_amount == EOK


def test_pointer_answer_to_confirm_question_keeps_amount():
    assert drop_carried_conditions("네 그거요", goal(EOK), ASKED_CONFIRM).goal_amount == EOK


@pytest.mark.parametrize(("hist", "text"), [(ASKED_ROLE, "모으는 돈이요"), (ASKED_CONFIRM, "네 그거요")])
async def test_pointer_answer_reaches_calc(hist, text):
    """모델이 가리키는 답을 읽고 부른 계산이 필터·단위 대조를 지나 Spring까지 간다"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "goal_amount": EOK}),
        text_resp("RECALC_RESULT", "목표 금액을 바꿔 계산했어요."),
    ])
    calc = FakeCalc()
    agent = ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", history=hist)
    resp = await agent.run(req)
    assert calc.calls == [{"goal_amount": EOK}]
    assert resp.message_type == "RECALC_RESULT"


# ---- 수용 기준 2·3: 누적 금지(D-041)는 그대로다 ----

def test_answer_after_recalc_result_still_drops_amount():
    """직전 답이 되묻기가 아니라 계산 결과면 앞 턴 금액을 이어받지 않는다"""
    assert not drop_carried_conditions("그거요", goal(EOK), AFTER_RECALC).changed()


def test_answer_stating_other_condition_still_drops_amount():
    changes = ConditionChanges.model_validate({"goal_amount": EOK, "target_period_months": 60})
    out = drop_carried_conditions("그럼 기간 5년이면?", changes, ASKED_ROLE)
    assert out.changed() == {"target_period_months": 60}


def test_amount_from_earlier_turn_is_not_kept():
    hist = history(
        ("user", "목표 1억으로 바꾸면?"),
        ("assistant", "목표 금액을 1억원으로 바꿔 계산했어요."),
        ("user", "다른 것도 바꿔 볼래"),
        ("assistant", "어떤 조건을 바꿔 볼까요?"),
    )
    assert not drop_carried_conditions("그거요", goal(EOK), hist).changed()


def test_two_amounts_before_question_are_not_kept():
    hist = history(
        ("user", "1억이랑 200만원으로 바꿀래"),
        ("assistant", "어느 조건을 어떤 금액으로 바꿀지 알려 주시겠어요?"),
    )
    assert not drop_carried_conditions("그거요", goal(EOK), hist).changed()


def test_amount_not_said_before_question_is_not_kept():
    assert not drop_carried_conditions("모으는 돈이요", goal(2 * EOK), ASKED_ROLE).changed()


def test_without_history_pointer_answer_drops_amount():
    assert not drop_carried_conditions("그거요", goal(EOK)).changed()


# ---- 수용 기준 4: 프롬프트 ----

def test_role_rule_maps_saving_money_without_monthly_to_goal():
    rule = bullet("역할 규칙")
    assert "모으는 돈" in rule


def test_choice_answer_rule_covers_pointer_answers_but_asks_when_several_listed():
    rule = bullet("조건 고르기 답 규칙:")
    assert "그거요" in rule
    assert "모으는 돈이요" in rule
    assert "두 조건 이상" in rule
