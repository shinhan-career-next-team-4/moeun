"""Issue #21: 대화 조건 4개 축소(도구 스키마·ConditionChanges·프롬프트·되묻기 규칙).

네트워크 없이 FakeClaude/FakeCalc로 검증한다. 프롬프트는 이슈에 글자 그대로 적힌 전용 문구와
핵심 키워드로만 검사한다(문장 전체에 묶지 않음). 모델이 프롬프트를 지키는지는 FakeClaude로 보장할 수
없으므로(D-073) 수동 평가 몫이다.
"""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock
from pydantic import ValidationError

from app.llm.agent import TOOLS, ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ChatResponse, ConditionChanges, DocType, RetrievedChunk

FIVE_KEYS = {
    "goal_amount", "monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months",
}
SIX_KEYS = FIVE_KEYS | {"etf_product_code"}   # 숫자 5개 + ETF 종목(D-097)
SEVEN_KEYS = SIX_KEYS | {"goal_custom_name"}   # + 목표 이름(#345)
REMOVED_KEYS = {"held_assets", "existing_monthly_contribution"}
UNCHANGEABLE_COPY = (
    "목표 종류·기존 납입액은 여기서 바꿀 수 없어요. 보유 자산은 입력 수정에서 바꿀 수 있어요."
)
NULLS = dict.fromkeys(sorted(FIVE_KEYS))
PLAN_CONTEXT = json.loads(
    (Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json")
    .read_text(encoding="utf-8")
)   # planContext 최종본(#35). 옛 모양은 422다


# ---- fake (tests/test_agent.py와 같은 모양. 파일끼리 묶이지 않도록 따로 둔다) ----

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


def recalc_schema() -> dict[str, Any]:
    tool = next(t for t in TOOLS if t["name"] == "recalculate")
    return dict(tool["input_schema"])


def last_tool_result(claude: FakeClaude) -> dict[str, Any]:
    return claude.calls[-1]["messages"][-1]["content"][0]


# ---- 수용 기준 1, 9: 도구 스키마와 ConditionChanges가 같은 4키 ----

def test_recalculate_tool_schema_has_only_seven_keys():
    """도구 스키마 필드는 목표 금액·월 저축액·비중·적금 금리·기한·ETF 종목·목표 이름 7개다 (D-092·D-097·#345)"""
    schema = recalc_schema()
    assert set(schema["properties"]) == SEVEN_KEYS
    assert set(schema["required"]) == SEVEN_KEYS
    assert schema["additionalProperties"] is False


def test_condition_changes_model_has_only_seven_keys():
    """ConditionChanges 필드는 7키뿐이고 보유 자산(2단계)·기존 납입액이 없다 (D-090·D-097·#345)"""
    assert set(ConditionChanges.model_fields) == SEVEN_KEYS
    assert not REMOVED_KEYS & set(ConditionChanges.model_fields)
    assert set(ConditionChanges.model_fields) == set(recalc_schema()["properties"])


def test_condition_changes_json_aliases_are_camel_case_seven_keys():
    """JSON 별칭(Spring 계약 internal-calc C2-2)도 camelCase 7개다"""
    props = ConditionChanges.model_json_schema(by_alias=True)["properties"]
    assert set(props) == {
        "goalAmount", "monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths",
        "etfProductCode", "goalCustomName",
    }


@pytest.mark.parametrize("removed", sorted(REMOVED_KEYS))
def test_condition_changes_rejects_removed_keys(removed):
    """제외 키는 extra=forbid로 거절된다"""
    with pytest.raises(ValidationError):
        ConditionChanges.model_validate({removed: 1})


# ---- 수용 기준 2: 목표 금액 계산 허용 및 변경 불가 조건 ----

async def test_goal_amount_utterance_calls_recalculate():
    """목표 금액 발화는 recalculate를 부르고 RECALC_RESULT로 응답한다 (D-090)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "goal_amount": 100_000_000}),
        text_resp("RECALC_RESULT", "목표 1억 기준 결과입니다"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("목표를 1억으로 바꿔줘", plan_context=PLAN_CONTEXT))
    assert calc.calls == [{"goal_amount": 100_000_000}]
    assert resp.message_type == "RECALC_RESULT"
    assert resp.condition_changes == {"goal_amount": 100_000_000}


@pytest.mark.parametrize(
    ("text", "wrong_amount"),
    [("목표 1억으로", 10_000), ("목표 2,500만원으로", 250_000_000)],
)
async def test_goal_amount_unit_mismatch_is_tool_error_without_calc(text, wrong_amount):
    """만·억 단위 오환산은 Spring 호출 전 check_amount_units에서 걸러 CLARIFY로 되묻는다 (CF12-9-3)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "goal_amount": wrong_amount}),
        text_resp("CLARIFY", "정확한 목표 금액을 다시 알려 주세요"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question(text))
    assert last_tool_result(claude)["is_error"] is True
    assert calc.calls == []
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None


async def test_recalculate_with_held_assets_key_is_tool_error_and_not_forwarded():
    """1단계에서 제외된 held_assets를 넣어 recalculate를 부르면 is_error이고 계산을 부르지 않는다 (D-090)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "held_assets": 10_000_000}),
        text_resp("ANSWER", UNCHANGEABLE_COPY),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("보유 자산을 1,000만원으로 바꿔줘"))
    assert last_tool_result(claude)["is_error"] is True
    assert calc.calls == []
    assert resp.message_type == "ANSWER" and resp.calc_run_id is None


def test_system_prompt_contains_unchangeable_copy_and_five_conditions():
    """프롬프트에 변경 불가 전용 문구가 글자 그대로 있고 5조건을 안내한다 (D-090, D-092)"""
    assert UNCHANGEABLE_COPY in SYSTEM_PROMPT
    for keyword in ("목표 금액", "월 저축액", "비중", "적금 금리", "기한"):
        assert keyword in SYSTEM_PROMPT


async def test_refusal_copy_offers_five_changeable_conditions():
    """범위 밖 안내 문구는 5조건 기준이라 목표 금액도 포함한다 (D-090)"""
    claude = FakeClaude([SimpleNamespace(stop_reason="refusal", content=[])])
    resp = await make_agent(claude, FakeCalc()).run(question("이 종목 사도 돼?"))
    assert resp.message_type == "OUT_OF_SCOPE"
    assert "목표 금액" in resp.content
    assert "월 저축액" in resp.content


# ---- 수용 기준 3: 모호한 발화 / 전부 null (예외 상황) ----

async def test_all_null_recalculate_is_tool_error_then_clarify_without_result():
    """(예외) 4키 전부 null이면 is_error 후 CLARIFY, 비교 결과를 만들지 않는다 (FR-AI-06)"""
    claude = FakeClaude([
        tool_resp("recalculate", NULLS),
        text_resp("CLARIFY", "무엇을 바꿔 볼까요?"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("좀 바꿔줘"))
    assert last_tool_result(claude)["is_error"] is True
    assert calc.calls == []
    assert resp.message_type == "CLARIFY"
    assert resp.calc_run_id is None and resp.condition_changes is None


# ---- 수용 기준 4: 범위 밖 숫자는 거절하지 않는다 (D-033) ----

@pytest.mark.parametrize(
    "changes",
    [
        {"monthly_saving_amount": 12_000_000},
        {"monthly_saving_amount": -1},
        {"savings_ratio_pct": 150},
        {"savings_ratio_pct": -10},
        {"savings_rate_input_pct": -1.5},
        {"target_period_months": 0},
        {"target_period_months": -6},
    ],
)
async def test_out_of_range_numbers_are_forwarded_to_spring_not_rejected(changes):
    """범위 밖 숫자도 타입만 맞으면 그 값 그대로 Spring 계산으로 보낸다 (D-033, 계약 원칙 2)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, **changes}),
        text_resp("ANSWER", "Spring이 판단합니다"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("이 값으로 바꿔줘"))
    assert calc.calls == [changes]
    assert resp.condition_changes == changes


def test_no_range_numbers_in_model_schema_tool_schema_or_prompt():
    """범위 수치가 모델·도구 스키마·프롬프트에 없다 (D-033)"""
    range_keys = {"minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"}
    model_props = ConditionChanges.model_json_schema()["properties"].values()
    tool_props = recalc_schema()["properties"].values()

    def keys_of(prop: dict[str, Any]) -> set[str]:
        nested = [k for branch in prop.get("anyOf", []) for k in branch]
        return set(prop) | set(nested)

    for prop in [*model_props, *tool_props]:
        assert not keys_of(prop) & range_keys
    for needle in ("0~100", "0-100", "100%", "12000000", "1200만"):
        assert needle not in SYSTEM_PROMPT


# ---- 수용 기준 5 (예외): 숫자가 아닌 값 ----

@pytest.mark.parametrize(
    "changes",
    [
        {"monthly_saving_amount": "500000"},
        {"monthly_saving_amount": True},
        {"savings_ratio_pct": "30"},
        {"savings_ratio_pct": False},
        {"savings_rate_input_pct": "3.5"},
        {"savings_rate_input_pct": True},
        {"target_period_months": "24"},
        {"target_period_months": True},
    ],
)
async def test_non_numeric_values_are_tool_error_and_calc_not_called(changes):
    """(예외) 문자열·불리언은 타입 검사에서 걸러 계산 도구를 부르지 않는다 (FR-AI-06)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, **changes}),
        text_resp("CLARIFY", "숫자로 알려 주세요"),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("이 값으로 바꿔줘"))
    assert last_tool_result(claude)["is_error"] is True
    assert calc.calls == []
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None


@pytest.mark.parametrize("changes", [{"monthly_saving_amount": "500000"}, {"target_period_months": True}])
def test_condition_changes_validation_rejects_non_numeric(changes):
    """(예외) ConditionChanges 검증이 문자열·불리언을 직접 거른다 (StrictInt/StrictFloat)"""
    with pytest.raises(ValidationError):
        ConditionChanges.model_validate(changes)


# ---- 수용 기준 6, 7: 상대 표현 되묻기 (규칙 7, D-059) ----

async def test_relative_monthly_saving_utterance_clarifies_without_recalculate():
    """'월 10만원 더'는 recalculate 없이 CLARIFY이고 display만 모델에 전달된다 (규칙 7, NFR-INT-01)"""
    claude = FakeClaude([text_resp("CLARIFY", "지금은 약 30만원이에요. 얼마로 바꿔 볼까요?")])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("월 10만원 더 넣으면 어떻게 돼?", plan_context=PLAN_CONTEXT))
    assert calc.calls == []
    assert resp.message_type == "CLARIFY"
    assert resp.calc_run_id is None and resp.condition_changes is None
    sent = claude.calls[0]["messages"][-1]["content"]
    assert "약 30만원" in sent  # display 표시값만 전달


RELATIVE_UTTERANCES = [
    pytest.param("월 10만원 더 넣으면?", id="monthly_saving"),
    pytest.param("적금 비중을 10% 늘리면?", id="savings_ratio"),
    pytest.param("적금 금리가 0.5% 덜 나오면?", id="savings_rate"),
    pytest.param("기간을 6개월 줄이면?", id="target_period"),
]


@pytest.mark.parametrize("text", RELATIVE_UTTERANCES)
async def test_relative_expression_on_each_of_four_conditions_clarifies(text):
    """네 조건 각각의 상대 표현이 모두 recalculate 없이 CLARIFY다 (규칙 7, 월 저축액만 해당하지 않음)"""
    claude = FakeClaude([text_resp("CLARIFY", "바꿀 값을 알려 주세요")])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question(text, plan_context=PLAN_CONTEXT))
    assert calc.calls == []
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None


def test_system_prompt_has_relative_expression_rule_for_all_four_conditions():
    """프롬프트 규칙 7: 상대 표현은 계산하지 않고 절대값을 되묻는다, 4조건 모두 (D-059)"""
    for keyword in ("더", "덜", "늘리면", "줄이면"):
        assert keyword in SYSTEM_PROMPT
    assert "절대값" in SYSTEM_PROMPT
    assert "CLARIFY" in SYSTEM_PROMPT
    # 규칙은 월 저축액에 한정되지 않고 4조건을 모두 나열한다
    for keyword in ("월 저축액", "비중", "적금 금리", "기한"):
        assert keyword in SYSTEM_PROMPT


# ---- 수용 기준 8 (예외): 절대값 발화는 되묻지 않는다 ----

async def test_absolute_value_utterance_calls_recalculate():
    """(예외) '월 40만원으로 하면'은 되묻지 않고 recalculate를 부른다 (되묻기가 정상 발화를 막지 않음)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 400_000}),
        text_resp("ANSWER", "월 40만원이면..."),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("월 40만원으로 하면?", plan_context=PLAN_CONTEXT))
    assert calc.calls == [{"monthly_saving_amount": 400_000}]
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 77


# ---- 수용 기준 10: C12b-14 키 표기와 응답 계약 ----

async def test_condition_changes_keys_stay_snake_case_for_all_five_keys():
    """condition_changes 키는 snake_case 그대로이고 camelCase 변환을 하지 않는다 (C12b-14)"""
    changes = {
        "goal_amount": 40_000_000,
        "monthly_saving_amount": 400_000,
        "savings_ratio_pct": 30,
        "savings_rate_input_pct": 3.5,
        "target_period_months": 24,
    }
    claude = FakeClaude([tool_resp("recalculate", changes), text_resp("ANSWER", "결과")])
    resp = await make_agent(claude, FakeCalc()).run(question("목표 4000만원으로 이렇게 바꿔줘"))
    assert resp.condition_changes == changes
    assert set(resp.condition_changes or {}) == FIVE_KEYS
    dumped = resp.model_dump(by_alias=True)["conditionChanges"]  # dict 키는 별칭 변환 대상이 아니다
    assert set(dumped) == FIVE_KEYS


def test_chat_response_has_no_new_fields():
    """ChatResponse에 새 필드를 만들지 않는다 (C12b-14)"""
    assert set(ChatResponse.model_fields) == {
        "message_type", "content", "baseline_scenario", "calc_run_id", "condition_changes", "citation_chunk_ids",
    }


# ---- 수용 기준 11 (예외): 예시 지어내기 금지 ----

def test_system_prompt_forbids_inventing_example_values():
    """프롬프트가 예시 금액·금리·기간을 지어내지 않도록 규칙을 둔다 (D-059 보강)"""
    assert "지어내" in SYSTEM_PROMPT
    assert "예시" in SYSTEM_PROMPT


# ---- 수용 기준 12: D-064 조건 고르기 문장 (모델 준수는 수동 평가, D-073) ----

def test_system_prompt_has_condition_picking_clarify_rule():
    """프롬프트에 값 없는 '조건 고르기' 문장은 계산하지 않고 CLARIFY로 값을 묻는 규칙이 있다 (D-064).
    모델이 실제로 지키는지는 자동 테스트로 보장할 수 없어 수동 평가 3~5건으로 확인한다(D-073)."""
    assert "조건 고르기" in SYSTEM_PROMPT


async def test_condition_picking_utterance_flow_clarifies_without_recalculate():
    """조건 고르기 발화('월 저축액을 바꿔 보고 싶어요')가 CLARIFY로 끝나면 계산·결과가 없다 (D-064)"""
    claude = FakeClaude([text_resp("CLARIFY", "월 저축액을 얼마로 바꿔 볼까요?")])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("월 저축액을 바꿔 보고 싶어요", plan_context=PLAN_CONTEXT))
    assert calc.calls == []
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None
