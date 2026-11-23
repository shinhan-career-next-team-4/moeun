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
# 계산 결과도 후속 질문으로 끝날 수 있다. 물음표만으로는 되묻기와 구별되지 않는다(리뷰 중간 1)
AFTER_RECALC = history(
    ("user", "목표 금액을 1억으로 바꾸면요?"),
    ("assistant", "목표 금액을 1억원으로 바꿔 계산했어요. 다른 조건도 바꿔 볼까요?"),
)
AFTER_ANSWER = history(
    ("user", "1억 모으려면 얼마나 걸려?"),
    ("assistant", "평균적인 경우 4년 2개월이 걸려요. 기간도 바꿔 볼까요?"),
)


def goal(amount: int) -> ConditionChanges:
    return ConditionChanges.model_validate({"goal_amount": amount})


# ---- 수용 기준 1: 가리키는 답은 되묻기 바로 앞 발화의 금액을 남긴다 ----

# 필터는 "그거요"처럼 어느 조건인지 모를 답도 막지 않는다. 되물을지는 모델이 정하고(프롬프트, S8-8f),
# 서버는 사용자가 말한 금액을 지우지 않는 쪽만 지킨다(D-073). 금액이 어느 조건인지도 거르지 않는다
@pytest.mark.parametrize("text", ["모으는 돈이요", "그거요", "첫 번째요"])
def test_pointer_answer_keeps_amount_from_utterance_before_question(text):
    assert drop_carried_conditions(text, goal(EOK), ASKED_ROLE).goal_amount == EOK


def test_full_width_question_mark_counts_as_question():
    hist = history(("user", "1억으로 바꿀게"), ("assistant", "어느 조건을 바꿀까요\uff1f"))
    assert drop_carried_conditions("모으는 돈이요", goal(EOK), hist).goal_amount == EOK


async def test_pointer_answer_reaches_calc():
    hist, text = ASKED_ROLE, "모으는 돈이요"
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

@pytest.mark.parametrize("hist", [AFTER_RECALC, AFTER_ANSWER])
@pytest.mark.parametrize("text", ["네 좋아요", "고마워요", "그거요"])
def test_answer_after_role_stated_turn_still_drops_amount(hist, text):
    """앞 발화가 역할을 말했으면 답은 되묻기가 아니므로(질문으로 끝나도) 앞 턴 금액을 이어받지 않는다"""
    assert not drop_carried_conditions(text, goal(EOK), hist).changed()


def test_clarify_without_question_mark_does_not_keep_amount():
    """물음표 없는 되묻기는 가리키는 답으로 보지 않는다(값을 지우는 쪽으로 틀린다)"""
    hist = history(("user", "1억으로 바꿀게"), ("assistant", "어느 조건인지 알려 주세요."))
    assert not drop_carried_conditions("모으는 돈이요", goal(EOK), hist).changed()


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
    assert '매달·월·한 달·지금 없이 "모으는 돈" → 목표 금액' in rule


def test_choice_answer_rule_covers_pointer_answers_but_asks_for_bare_pointer():
    rule = bullet("조건 고르기 답 규칙:")
    assert "모으는 돈이요" in rule
    assert "그거요" in rule
    assert "다시 되묻습니다" in rule
    assert "물음표로 끝냅니다" in rule
