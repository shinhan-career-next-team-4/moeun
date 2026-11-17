"""Issue #22: 이전 대화의 조건을 이번 계산에 누적하지 않는다(M-3, D-041).

네트워크 없이 FakeClaude/FakeCalc로 검증한다. 모델이 프롬프트를 지키는지는 FakeClaude로 보장할 수
없으므로 수동 평가 몫이다(D-073). 여기서는 (1) 프롬프트에 규칙 문구가 있는지, (2) 모델이 어겨서
이전 값을 recalculate에 다시 넣어도 서버가 계산 전에 걸러 내는지(drop_carried_conditions)만 검사한다.
"""

import json
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.llm.agent import ChatAgent, drop_carried_conditions
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk

NULLS = dict.fromkeys(["monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"])
PLAN_CONTEXT = {"monthlySavingAmount": {"value": 300_000, "display": "약 30만원"}}
PREVIOUS_HISTORY = [
    {"role": "user", "content": "월 50만원으로 바꾸면?"},
    {"role": "assistant", "content": "월 50만원으로 바꾸면 최종 금액이 늘어나요."},
]


# ---- fake (tests/test_agent_tools.py와 같은 모양. 파일끼리 묶이지 않도록 따로 둔다) ----

def text_resp(message_type: str, content: str = "답") -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(name: str, input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name=name, input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeCalc:
    def __init__(self):
        self.calls: list[dict[str, Any]] = []

    async def recalculate(self, member_id, baseline, changes):
        self.calls.append(changes.changed())
        return {"calcRunId": 77, "goodCase": {"finalAmount": 10_500_000}}


class FakeRetriever:
    async def search(self, query, doc_type: DocType | None = None, top_k=None) -> list[RetrievedChunk]:
        return []


def make_agent(claude, calc):
    return ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text: str, **kw) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


def last_tool_result(claude: FakeClaude) -> dict[str, Any]:
    return claude.calls[-1]["messages"][-1]["content"][0]


# ---- 수용 기준 1: 이전 턴 조건을 이번 계산에 넣지 않는다 (history는 그대로 전달) ----

async def test_second_turn_recalculates_with_only_this_utterance_condition():
    """'월 50만원' 다음 '기간도 5년으로'는 기간만 계산하고 월 저축액은 넣지 않는다 (AC1, M-3, D-041)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "target_period_months": 60}),
        text_resp("RECALC_RESULT", "기간 5년이면..."),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(
        question("기간도 5년으로", plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY)
    )
    assert calc.calls == [{"target_period_months": 60}]  # monthly_saving_amount 없음
    assert resp.condition_changes == {"target_period_months": 60}
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 77
    # history는 지우지 않고 모델에 그대로 전달한다(대화 맥락 유지)
    sent = claude.calls[0]["messages"][:3]  # 같은 리스트에 도구 결과가 이어 붙으므로 앞 3개만 본다
    assert [m["role"] for m in sent] == ["user", "assistant", "user"]
    assert "월 50만원으로 바꾸면?" in sent[0]["content"]
    assert "최종 금액이 늘어나요" in sent[1]["content"]


# ---- 수용 기준 2: 모델이 이전 값을 다시 넣어도 서버가 걸러 낸다 ----

async def test_model_reinjecting_previous_condition_is_filtered_before_calc():
    """모델이 이전 월 저축액을 재주입해도 이번 발화에 없으면 계산 인자·응답에서 뺀다 (AC2, M-3)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000, "target_period_months": 60}),
        text_resp("RECALC_RESULT", "기간 5년이면..."),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(
        question("기간도 5년으로", plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY)
    )
    assert calc.calls == [{"target_period_months": 60}]
    assert resp.condition_changes == {"target_period_months": 60}
    assert resp.calc_run_id == 77


async def test_only_carried_condition_left_is_tool_error_then_clarify():
    """(예외) 이번 발화가 다른 조건만 말했는데 모델이 이전 값만 넣으면 계산 없이 되묻는다 (AC2 경계, FR-AI-06)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("CLARIFY", "무엇을 바꿔 볼까요?"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(
        question("기간은 그대로 두고 다시 해줘", plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY)
    )
    assert last_tool_result(claude)["is_error"] is True
    assert calc.calls == []
    assert resp.message_type == "CLARIFY"
    assert resp.calc_run_id is None and resp.condition_changes is None


# ---- 오탐 방지: 이번 발화의 조건은 지우지 않는다 ----

async def test_both_conditions_stated_in_this_utterance_are_kept():
    """'월 40만원으로, 기간은 3년으로'는 두 값 모두 유지한다 (오탐 방지, M-3)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 400_000, "target_period_months": 36}),
        text_resp("RECALC_RESULT", "결과"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(
        question("월 40만원으로, 기간은 3년으로", plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY)
    )
    assert calc.calls == [{"monthly_saving_amount": 400_000, "target_period_months": 36}]
    assert resp.condition_changes == {"monthly_saving_amount": 400_000, "target_period_months": 36}


@pytest.mark.parametrize(
    ("text", "changes"),
    [
        pytest.param("월 40만원으로 하면?", {"monthly_saving_amount": 400_000}, id="monthly_saving"),
        pytest.param("적금 비중을 30%로 하면?", {"savings_ratio_pct": 30}, id="savings_ratio"),
        pytest.param("적금 금리가 3.5%면?", {"savings_rate_input_pct": 3.5}, id="savings_rate"),
        pytest.param("기간을 2년으로 하면?", {"target_period_months": 24}, id="target_period"),
    ],
)
async def test_normal_utterance_for_each_of_four_conditions_is_kept(text, changes):
    """네 조건 각각의 정상 발화는 필터에 지워지지 않고 그대로 계산된다 (오탐 방지, M-3)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, **changes}),
        text_resp("RECALC_RESULT", "결과"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question(text, plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY))
    assert calc.calls == [changes]
    assert resp.condition_changes == changes
    assert resp.calc_run_id == 77


# ---- drop_carried_conditions 표 테스트 (순수 함수) ----

def changes_of(**kw: Any) -> ConditionChanges:
    return ConditionChanges.model_validate(kw)


ALL_FOUR = {
    "monthly_saving_amount": 500_000,
    "savings_ratio_pct": 30,
    "savings_rate_input_pct": 3.5,
    "target_period_months": 60,
}

# 발화가 가리키는 조건은 유지하고 나머지는 null. 표현도 숫자도 없으면 전부 null, 숫자만 있으면 전부 유지
DROP_CASES = [
    pytest.param("그냥 다시 해줘", ALL_FOUR, {}, id="no_wording_no_number_drops_all"),
    pytest.param("이번엔 60으로", ALL_FOUR, ALL_FOUR, id="number_only_keeps_all"),
    pytest.param("기간을 60개월로", ALL_FOUR, {"target_period_months": 60}, id="months_is_not_monthly_saving"),
    pytest.param("금리 4%면?", ALL_FOUR, {"savings_rate_input_pct": 3.5}, id="percent_with_rate_word_is_rate_only"),
    pytest.param("적금 금리 4%로", ALL_FOUR, {"savings_rate_input_pct": 3.5}, id="savings_word_with_rate_word"),
    pytest.param("ETF 비중 30%로", ALL_FOUR, {"savings_ratio_pct": 30}, id="percent_with_ratio_word_is_ratio_only"),
    pytest.param("원래대로 하고 3년으로", ALL_FOUR, {"target_period_months": 60}, id="wonrae_is_not_won"),
    pytest.param("매달 40만으로 하면?", ALL_FOUR, {"monthly_saving_amount": 500_000}, id="maedal_40man"),
    pytest.param("한 달에 40만", ALL_FOUR, {"monthly_saving_amount": 500_000}, id="han_dal"),
    pytest.param(
        "3.5프로로 하고 3년이면?", ALL_FOUR,
        {"savings_ratio_pct": 30, "savings_rate_input_pct": 3.5, "target_period_months": 60}, id="peuro_with_period",
    ),
    pytest.param(
        "주식 쪽을 70으로 하고 2년", ALL_FOUR, {"savings_ratio_pct": 30, "target_period_months": 60}, id="stock_ratio"
    ),
    pytest.param(
        "적금 40%에 금리 4%", ALL_FOUR, {"savings_ratio_pct": 30, "savings_rate_input_pct": 3.5}, id="ratio_and_rate"
    ),
    pytest.param(
        "적금 비율 40%, 이율 4%", ALL_FOUR, {"savings_ratio_pct": 30, "savings_rate_input_pct": 3.5}, id="ratio_word_2"
    ),
    pytest.param(
        "4퍼, 3년", ALL_FOUR,
        {"savings_ratio_pct": 30, "savings_rate_input_pct": 3.5, "target_period_months": 60}, id="peo_unit",
    ),
    pytest.param("50으로 하고 기간은 3년", ALL_FOUR, ALL_FOUR, id="unitless_number_with_period_keeps_all"),
    pytest.param(
        "오십만, 3년", ALL_FOUR, {"monthly_saving_amount": 500_000, "target_period_months": 60}, id="korean_numeral"
    ),
    # 쉼표 금액과 한 조건 안의 복수 숫자는 숫자 하나로 센다. 숫자가 조건보다 많다고 필터가 꺼지면 안 된다
    pytest.param("월 500,000원", ALL_FOUR, {"monthly_saving_amount": 500_000}, id="comma_amount"),
    pytest.param(
        "월 1,000,000원, 기간 5년", ALL_FOUR,
        {"monthly_saving_amount": 500_000, "target_period_months": 60}, id="comma_amount_and_period",
    ),
    pytest.param("3년 6개월", ALL_FOUR, {"target_period_months": 60}, id="years_and_months"),
    pytest.param("5년(60개월)", ALL_FOUR, {"target_period_months": 60}, id="years_with_months_in_parens"),
    pytest.param("60개월(5년)로", ALL_FOUR, {"target_period_months": 60}, id="months_with_years_in_parens"),
    # 사용자가 말한 값은 지우지 않는다(모호하면 남긴다)
    pytest.param(
        "기간 5년, 반반으로", {**ALL_FOUR, "savings_ratio_pct": 50},
        {"savings_ratio_pct": 50, "target_period_months": 60}, id="banban_with_period",
    ),
    pytest.param("반반으로", {**ALL_FOUR, "savings_ratio_pct": 50}, {"savings_ratio_pct": 50}, id="banban_only"),
    pytest.param(
        "적립금 사십으로 하고 기간 3년", ALL_FOUR,
        {"monthly_saving_amount": 500_000, "target_period_months": 60}, id="jeokrip_korean_numeral",
    ),
    pytest.param("저축 기간을 5년으로", ALL_FOUR, {"target_period_months": 60}, id="savings_period_is_period"),
    pytest.param("기간만 원래대로 돌리고 3년", ALL_FOUR, {"target_period_months": 60}, id="man_won_across_words"),
    pytest.param("기간도 5년으로", ALL_FOUR, {"target_period_months": 60}, id="only_period_keyword"),
    pytest.param("월 40만원으로", ALL_FOUR, {"monthly_saving_amount": 500_000}, id="only_monthly_keyword"),
    pytest.param("금리를 3.5로", ALL_FOUR, {"savings_rate_input_pct": 3.5}, id="only_rate_keyword"),
    pytest.param("ETF 비중을 늘려서", ALL_FOUR, {"savings_ratio_pct": 30}, id="only_ratio_keyword"),
    pytest.param(
        "월 40만원으로, 기간은 3년으로",
        {"monthly_saving_amount": 400_000, "target_period_months": 36},
        {"monthly_saving_amount": 400_000, "target_period_months": 36},
        id="both_stated_kept",
    ),
    pytest.param(
        "금리 3%로 하고 기간은 2년으로",
        {"savings_rate_input_pct": 3, "target_period_months": 24, "monthly_saving_amount": 500_000},
        {"savings_rate_input_pct": 3, "target_period_months": 24},
        id="mixed_keeps_stated_drops_carried",
    ),
]


@pytest.mark.parametrize(("text", "given", "expected"), DROP_CASES)
def test_drop_carried_conditions_table(text, given, expected):
    """이번 발화에 조건 표현이 하나도 없을 때만 그 값을 null로 만든다 (AC2, M-3)"""
    out = drop_carried_conditions(text, ConditionChanges.model_validate(given))
    assert isinstance(out, ConditionChanges)
    assert out.changed() == expected


# 조건별 허용 키워드. 하나라도 들어 있으면 그 조건은 유지한다(모호하면 유지)
KEEP_KEYWORDS = [
    *[
        pytest.param("monthly_saving_amount", 400_000, kw, id=f"monthly-{kw}")
        for kw in ("월", "저축", "납입", "만원", "40원", "매달")
    ],
    *[pytest.param("savings_ratio_pct", 30, kw, id=f"ratio-{kw}") for kw in ("비중", "적금", "ETF", "%", "퍼센트")],
    *[pytest.param("savings_rate_input_pct", 3.5, kw, id=f"rate-{kw}") for kw in ("금리", "이율", "%")],
    *[pytest.param("target_period_months", 24, kw, id=f"period-{kw}") for kw in ("기간", "기한", "개월", "6달", "년")],
]


@pytest.mark.parametrize(("field", "value", "keyword"), KEEP_KEYWORDS)
def test_drop_carried_conditions_keeps_value_when_keyword_present(field, value, keyword):
    """각 조건의 키워드가 발화에 있으면 값을 유지한다 (오탐 방지, 모호하면 유지)"""
    out = drop_carried_conditions(f"{keyword} 바꿔서", changes_of(**{field: value}))
    assert out.changed() == {field: value}


@pytest.mark.parametrize(
    ("field", "value", "text"),
    [
        ("monthly_saving_amount", 500_000, "기간도 5년으로"),
        ("savings_ratio_pct", 30, "기간도 5년으로"),
        ("savings_rate_input_pct", 3.5, "기간도 5년으로"),
        ("target_period_months", 60, "월 40만원으로"),
    ],
)
def test_drop_carried_conditions_drops_each_field_when_other_condition_is_stated(field, value, text):
    """다른 조건만 말한 발화에서는 네 조건 각각이 null이 된다 (AC2)"""
    out = drop_carried_conditions(text, changes_of(**{field: value}))
    assert out.changed() == {}


def test_drop_carried_conditions_drops_all_when_no_wording_and_no_number():
    """말한 값이 없는 발화("그럼 어떻게 돼?")에서는 모델이 넣은 값이 전부 이어받은 값이다 (AC2)"""
    out = drop_carried_conditions("그럼 어떻게 돼?", changes_of(**ALL_FOUR))
    assert out.changed() == {}


async def test_no_history_skips_filter_so_first_question_is_forwarded():
    """history가 없으면 이어받을 값이 없으므로 필터를 거치지 않고 Spring으로 보낸다 (D-033, #21과 같은 동작)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 400_000}),
        text_resp("RECALC_RESULT", "결과"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("매달 40만으로 하면?", plan_context=PLAN_CONTEXT))
    assert calc.calls == [{"monthly_saving_amount": 400_000}]
    assert resp.condition_changes == {"monthly_saving_amount": 400_000}


async def test_dropped_conditions_are_reported_to_model_in_tool_result():
    """뺀 조건은 도구 결과로 모델에 알린다. 모델이 뺀 조건을 계산에 쓴 것처럼 답하지 않게 한다 (서버 사실)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000, "target_period_months": 60}),
        text_resp("RECALC_RESULT", "기간만 바꾼 결과입니다"),
    ])
    calc = FakeCalc()
    await make_agent(claude, calc).run(question("기간도 5년으로", plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY))
    result = json.loads(last_tool_result(claude)["content"])
    assert result["excludedConditions"] == ["monthly_saving_amount"]
    assert "note" in result


async def test_nothing_dropped_leaves_tool_result_without_note():
    """뺀 조건이 없으면 도구 결과는 Spring 응답 그대로다"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "target_period_months": 60}),
        text_resp("RECALC_RESULT", "결과"),
    ])
    await make_agent(claude, FakeCalc()).run(
        question("기간도 5년으로", plan_context=PLAN_CONTEXT, history=PREVIOUS_HISTORY)
    )
    assert "excludedConditions" not in json.loads(last_tool_result(claude)["content"])


def test_drop_carried_conditions_does_not_mutate_input():
    """입력 ConditionChanges는 바꾸지 않고 새 값을 돌려준다 (서버 사실 보호)"""
    given = changes_of(**ALL_FOUR)
    drop_carried_conditions("기간도 5년으로", given)
    assert given.changed() == ALL_FOUR


# ---- 프롬프트 규칙 (모델 준수는 수동 평가, D-073) ----

@pytest.mark.parametrize("phrase", ["이번 발화", "다시 넣지", "내 계획"])
def test_system_prompt_has_no_accumulate_rule_phrases(phrase):
    """프롬프트에 누적 금지 규칙 문구가 있다: 이번 발화 조건만, 이전 값 재주입 금지, 비교 기준은 내 계획 (AC1, D-041).
    모델이 실제로 지키는지는 FakeClaude로 보장할 수 없어 수동 평가로 확인한다(D-073)."""
    assert phrase in SYSTEM_PROMPT


# ---- EXPLAIN 모드(text 없음)에서도 필터가 예외 없이 동작 ----

def test_drop_carried_conditions_accepts_empty_text():
    """(예외) 발화가 비어 있어도 필터는 예외 없이 ConditionChanges를 돌려준다 (EXPLAIN 모드 대비)"""
    out = drop_carried_conditions("", changes_of(monthly_saving_amount=500_000))
    assert out.changed() == {}


async def test_explain_mode_without_text_runs_without_error():
    """(예외) EXPLAIN은 text가 없는데 모델이 recalculate를 불러도 필터가 예외를 내지 않고 응답한다"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("EXPLANATION", "설명입니다"),
    ])
    calc = FakeCalc()
    req = ChatRequest(
        memberId=1, messageId=9, mode="EXPLAIN", calcRunId=5, baselineScenario="AVG", planContext=PLAN_CONTEXT
    )
    resp = await make_agent(claude, calc).run(req)
    assert calc.calls == [{"monthly_saving_amount": 500_000}]  # text가 없으면 필터를 건너뛴다
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}
    assert resp.content == "설명입니다"


async def test_explain_mode_without_recalculate_is_unchanged():
    """EXPLAIN 회귀: 도구 호출 없이 끝나면 EXPLANATION과 요청의 calc_run_id를 그대로 쓴다"""
    claude = FakeClaude([text_resp("EXPLANATION", "설명입니다")])
    req = ChatRequest(memberId=1, messageId=9, mode="EXPLAIN", calcRunId=5, baselineScenario="AVG")
    resp = await make_agent(claude, FakeCalc()).run(req)
    assert resp.message_type == "EXPLANATION"
    assert resp.calc_run_id == 5 and resp.condition_changes is None
