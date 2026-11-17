"""Issue #23(T-015): 실제 CalcClient + respx(Spring 대역) + FakeClaude로 계산 응답·오류 소비를 검증한다.

수용 기준(.loop/20261117-153324/spec.md) AC-1·3~10·13(CalcClient 경로)·14(실제로 보낸 요청 본문)를 맡는다.
네트워크 없음. 응답 본문은 docs/api/examples/internal-calc/*.json(계약 부록 A) 그대로 쓴다.
모델이 프롬프트를 지키는지는 FakeClaude로 보장할 수 없으므로(D-073) 문장 품질은 수동 확인 몫이고,
여기서는 모델에게 무엇을 넘기는지(도구 결과)와 서버가 정하는 사실(messageType, calcRunId)만 검사한다.
"""

import json
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
import respx
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.core.copy import load_copy
from app.llm.agent import AgentError, ChatAgent
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk, Scenario
from app.tools.calc import CalcClient, CalcRejected

REPO = Path(__file__).resolve().parents[2]
EXAMPLES = REPO / "docs" / "api" / "examples" / "internal-calc"
BASE = "http://spring.test"
NULLS = dict.fromkeys(["monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"])
RAW_AMOUNT_KEYS = {
    "afterTaxAsset", "principal", "afterTaxGain", "gapToGoal", "goalAmount", "annualReturnPct",
    "requiredMonthlySaving", "dataVersions", "goalReachMonth",
}  # fmt: skip


def example(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((EXAMPLES / name).read_text(encoding="utf-8"))
    return data


def problem(status: int, body: dict[str, Any]) -> httpx.Response:
    """Spring ProblemDetail 응답. Content-Type이 application/problem+json이어도 파싱해야 한다."""
    return httpx.Response(
        status, content=json.dumps(body, ensure_ascii=False), headers={"Content-Type": "application/problem+json"}
    )


# ---- fake (다른 테스트 파일과 묶이지 않도록 따로 둔다) ----

def text_resp(message_type: str = "RECALC_RESULT", content: str = "답") -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name="recalculate", input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[Any]):
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


@pytest.fixture
async def http():
    async with httpx.AsyncClient(base_url=BASE) as client:
        yield client


@pytest.fixture
def spring():
    with respx.mock(base_url=BASE, assert_all_called=False) as router:
        yield router.post("/internal/calc")


def make_agent(claude: FakeClaude, http: httpx.AsyncClient) -> ChatAgent:
    return ChatAgent(
        claude, CalcClient(http, "test-key"), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4
    )


def question(text: str = "월 저축액을 50만원으로 바꾸면?", **kw: Any) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


def monthly(amount: int = 500_000) -> dict[str, Any]:
    return {**NULLS, "monthly_saving_amount": amount}


def tool_result(claude: FakeClaude) -> dict[str, Any]:
    """모델에게 간 마지막 tool_result 블록(두 번째 모델 호출의 마지막 user 메시지)."""
    block: dict[str, Any] = claude.calls[-1]["messages"][-1]["content"][0]
    return block


# ---- AC-4: 422·409는 본문을 파싱한 구조화 예외 CalcRejected(code, errors) ----

@pytest.mark.parametrize(
    ("status", "file"),
    [
        (422, "validation-failed-out-of-range-response.json"),
        (422, "validation-failed-multi-response.json"),
        (422, "field-not-changeable-response.json"),
        (409, "no-plan-response.json"),
        (409, "already-exceeded-response.json"),
    ],
)
async def test_422_and_409_raise_calc_rejected_with_parsed_body(http, spring, status, file):
    body = example(file)
    spring.mock(return_value=problem(status, body))
    with pytest.raises(CalcRejected) as caught:
        await CalcClient(http, "k").recalculate(7, Scenario.AVG, ConditionChanges(monthly_saving_amount=12_000_000))
    assert caught.value.code == body["code"]
    assert caught.value.errors == body["errors"]


async def test_calc_rejected_does_not_truncate_long_error_body(http, spring):
    errors = [
        {"field": f"field{i}", "reason": "OUT_OF_RANGE", "min": 1, "max": 9, "unit": "KRW", "minDisplay": "1원",
         "maxDisplay": "9원"}
        for i in range(30)
    ]  # fmt: skip
    body = {**example("validation-failed-multi-response.json"), "errors": errors}
    assert len(json.dumps(body)) > 500
    spring.mock(return_value=problem(422, body))
    with pytest.raises(CalcRejected) as caught:
        await CalcClient(http, "k").recalculate(7, Scenario.AVG, ConditionChanges(monthly_saving_amount=1))
    assert caught.value.errors == errors


@pytest.mark.parametrize("status", [409, 422])
async def test_rejected_status_with_unparsable_body_fails_request(http, spring, status):
    spring.mock(return_value=httpx.Response(status, content="<html>proxy error</html>"))
    claude = FakeClaude([tool_resp(monthly())])
    with pytest.raises(AgentError) as caught:
        await make_agent(claude, http).run(question())
    assert caught.value.code == "CALC_FAILED"
    assert len(claude.calls) == 1


# ---- AC-3, AC-7: 400·401·5xx·타임아웃·연결 실패는 AgentError, 모델에 넘기지 않는다 ----

@pytest.mark.parametrize(
    ("response", "code"),
    [
        (problem(400, example("malformed-request-response.json")), "CALC_BAD_REQUEST"),
        (problem(401, example("invalid-internal-key-response.json")), "CALC_UNAUTHORIZED"),
        (httpx.Response(401), "CALC_UNAUTHORIZED"),
        (problem(500, example("calc-failed-response.json")), "CALC_FAILED"),
        (httpx.Response(503, content="Service Unavailable"), "CALC_FAILED"),
        (httpx.Response(502), "CALC_FAILED"),
    ],
)
async def test_status_maps_to_agent_error_without_calling_model_again(http, spring, response, code):
    spring.mock(return_value=response)
    claude = FakeClaude([tool_resp(monthly())])
    with pytest.raises(AgentError) as caught:
        await make_agent(claude, http).run(question())
    assert caught.value.code == code
    assert len(claude.calls) == 1   # 도구 호출을 낸 첫 호출뿐. 실패를 모델에 설명시키지 않는다


@pytest.mark.parametrize("error", [httpx.ConnectError("refused"), httpx.ConnectTimeout("t"), httpx.ReadTimeout("t")])
async def test_timeout_and_connection_failure_map_to_calc_unavailable(http, spring, error):
    spring.mock(side_effect=error)
    claude = FakeClaude([tool_resp(monthly())])
    with pytest.raises(AgentError) as caught:
        await make_agent(claude, http).run(question())
    assert caught.value.code == "CALC_UNAVAILABLE"
    assert len(claude.calls) == 1


# ---- AC-1: 422 VALIDATION_FAILED → 모델에 {code, errors}만, CLARIFY ----

OUT_OF_RANGE = example("validation-failed-out-of-range-response.json")


async def test_validation_failed_passes_only_code_and_errors_and_clarifies(http, spring):
    spring.mock(return_value=problem(422, OUT_OF_RANGE))
    claude = FakeClaude([tool_resp(monthly(12_000_000)), text_resp("CLARIFY", "1,000만원까지 가능해요. 얼마로 할까요?")])
    resp = await make_agent(claude, http).run(question("월 저축액을 1200만원으로 바꾸면?"))

    result = tool_result(claude)
    assert json.loads(result["content"]) == {"code": "VALIDATION_FAILED", "errors": OUT_OF_RANGE["errors"]}
    assert resp.message_type == "CLARIFY"
    assert resp.calc_run_id is None
    assert resp.condition_changes is None
    assert len(claude.calls) == 2


async def test_validation_failed_forces_clarify_even_if_model_says_otherwise(http, spring):
    spring.mock(return_value=problem(422, OUT_OF_RANGE))
    claude = FakeClaude([tool_resp(monthly(12_000_000)), text_resp("RECALC_RESULT", "계산했어요")])
    resp = await make_agent(claude, http).run(question())
    assert (resp.message_type, resp.calc_run_id, resp.condition_changes) == ("CLARIFY", None, None)


@pytest.mark.parametrize(
    "errors",
    [
        [{"field": "monthlySavingAmount", "reason": "OUT_OF_RANGE", "min": 5000, "max": 7_000_000, "unit": "KRW",
          "minDisplay": "0.5만원", "maxDisplay": "700만원"}],
        [{"field": "savingsRateInputPct", "reason": "OUT_OF_RANGE", "min": 0, "max": 20, "unit": "PCT",
          "minDisplay": "연 0.00%", "maxDisplay": "연 20.00%"}],
        [{"field": "targetPeriodMonths", "reason": "OUT_OF_RANGE", "min": 1, "max": 360, "unit": "MONTH",
          "minDisplay": "0년 1개월", "maxDisplay": "30년 0개월"}],
        [{"field": "savingsRateInputPct", "reason": "TOO_MANY_DECIMALS", "scale": 2}],
    ],
)  # fmt: skip
async def test_validation_errors_are_passed_through_unchanged_no_hardcoded_range(http, spring, errors):
    """범위 수치는 Spring이 준 그대로. ai-server가 값을 바꾸거나 자기 수치로 채우지 않는다(C3-3)."""
    spring.mock(return_value=problem(422, {**OUT_OF_RANGE, "errors": errors}))
    claude = FakeClaude([tool_resp(monthly(1)), text_resp("CLARIFY")])
    await make_agent(claude, http).run(question())
    assert json.loads(tool_result(claude)["content"]) == {"code": "VALIDATION_FAILED", "errors": errors}


# ---- AC-10: minDisplay·maxDisplay가 없으면(계약 위반) 모델이 계산할 원 단위 숫자를 주지 않는다 ----

@pytest.mark.parametrize("drop", [["minDisplay", "maxDisplay"], ["maxDisplay"], ["minDisplay"]])
async def test_out_of_range_without_display_strings_hides_raw_bounds_from_model(http, spring, drop):
    entry = {k: v for k, v in OUT_OF_RANGE["errors"][0].items() if k not in drop}
    spring.mock(return_value=problem(422, {**OUT_OF_RANGE, "errors": [entry]}))
    claude = FakeClaude([tool_resp(monthly(12_000_000)), text_resp("CLARIFY", "허용 범위를 벗어났어요. 다시 말씀해 주세요")])
    resp = await make_agent(claude, http).run(question())

    raw = tool_result(claude)["content"]
    sent = json.loads(raw)
    assert sent["code"] == "VALIDATION_FAILED"
    assert (sent["errors"][0]["field"], sent["errors"][0]["reason"]) == ("monthlySavingAmount", "OUT_OF_RANGE")
    assert not {"min", "max"} & set(sent["errors"][0])
    assert "10000000" not in raw and "10000" not in raw.replace("10000000", "")
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None


# ---- AC-6: 같은 요청 안에서 VALIDATION_FAILED 뒤에는 recalculate를 다시 부르지 않는다 ----

async def test_recalculate_is_not_called_again_after_validation_failed(http, spring):
    spring.mock(return_value=problem(422, OUT_OF_RANGE))
    claude = FakeClaude([
        tool_resp(monthly(12_000_000), "tu_1"),
        tool_resp(monthly(10_000_000), "tu_2"),   # 모델이 maxDisplay 값으로 자동 보정해 다시 부르려 한다
        text_resp("CLARIFY", "1,000만원까지 가능해요. 얼마로 할까요?"),
    ])
    resp = await make_agent(claude, http).run(question("월 저축액을 1200만원으로 바꾸면?"))

    assert spring.call_count == 1
    second = tool_result(claude)
    assert second["tool_use_id"] == "tu_2" and second["is_error"] is True
    assert (resp.message_type, resp.calc_run_id, resp.condition_changes) == ("CLARIFY", None, None)


# ---- AC-5, AC-13(D-071): 구조화 오류 3종은 모델 없이 문구표 고정 문장, ANSWER ----

@pytest.mark.parametrize(
    ("status", "file", "key"),
    [
        (422, "field-not-changeable-response.json", "chat.unchangeableCondition"),
        (409, "no-plan-response.json", "chat.noPlan"),
        (409, "already-exceeded-response.json", "chat.alreadyExceeded"),
    ],
)
async def test_structured_errors_answer_with_fixed_copy_without_calling_model_again(http, spring, status, file, key):
    spring.mock(return_value=problem(status, example(file)))
    claude = FakeClaude([tool_resp(monthly())])   # 두 번째 응답이 없다. 모델을 또 부르면 IndexError로 실패한다
    resp = await make_agent(claude, http).run(question())

    assert len(claude.calls) == 1
    assert resp.message_type == "ANSWER"
    assert resp.content == load_copy().get(key)
    assert resp.calc_run_id is None
    assert resp.condition_changes is None
    assert resp.citation_chunk_ids == []
    wire = resp.model_dump(by_alias=True)
    assert wire["messageType"] == "ANSWER" and wire["calcRunId"] is None


async def test_already_exceeded_with_four_condition_utterance_has_no_comparison_or_apply_data(http, spring):
    """D-071: 초과 계획에서 4개 조건을 바꾸는 발화도 같은 고정 문장 분기. 비교 결과·적용 버튼 재료가 없다."""
    spring.mock(return_value=problem(409, example("already-exceeded-response.json")))
    four = {
        "monthly_saving_amount": 600_000, "savings_ratio_pct": 30, "savings_rate_input_pct": 4.5,
        "target_period_months": 48,
    }  # fmt: skip
    claude = FakeClaude([tool_resp(four)])
    resp = await make_agent(claude, http).run(question("월 60만원, 적금 비중 30%, 금리 4.5%, 4년으로 바꾸면?"))

    assert len(claude.calls) == 1
    assert resp.message_type == "ANSWER" and resp.message_type != "RECALC_RESULT"
    assert resp.content == load_copy().get("chat.alreadyExceeded")
    assert resp.condition_changes is None and resp.calc_run_id is None


# ---- AC-8: 200 응답은 calcRunId 검사 후 추린 것만 모델에 넘긴다 ----

async def test_success_tool_result_contains_only_selected_fields(http, spring):
    body = example("success-response.json")
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly()), text_resp("RECALC_RESULT", "월 50만원이면...")])
    resp = await make_agent(claude, http).run(question())

    assert (resp.message_type, resp.calc_run_id) == ("RECALC_RESULT", body["calcRunId"])
    result = tool_result(claude)
    assert not result.get("is_error")
    sent = json.loads(result["content"])

    assert {"display", "diff", "conditions", "current", "changed"} <= set(sent)
    assert set(sent) <= {"calcRunId", "display", "diff", "conditions", "current", "changed"}
    assert sent["display"] == body["display"]
    assert sent["diff"] == body["diff"]
    assert sent["conditions"] == body["conditions"]
    for side in ("current", "changed"):
        assert set(sent[side]) <= {"baselineScenario", "scenarios", "assumptions"}
        assert sent[side]["baselineScenario"] == body[side]["baselineScenario"]
        assert sent[side]["scenarios"] == [
            {"scenario": s["scenario"], "isAchieved": s["isAchieved"]} for s in body[side]["scenarios"]
        ]
        assert sent[side]["assumptions"] == {"isDefaultSavingsRate": body[side]["assumptions"]["isDefaultSavingsRate"]}


async def test_success_tool_result_has_no_raw_amounts_outside_display(http, spring):
    body = example("success-response.json")
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly()), text_resp()])
    await make_agent(claude, http).run(question())

    outside_display = {k: v for k, v in json.loads(tool_result(claude)["content"]).items() if k != "display"}

    def keys(node: Any) -> set[str]:
        if isinstance(node, dict):
            return set(node) | {k for v in node.values() for k in keys(v)}
        if isinstance(node, list):
            return {k for v in node for k in keys(v)}
        return set()

    assert not RAW_AMOUNT_KEYS & keys(outside_display)
    assert "17120000" not in json.dumps(outside_display)   # current.AVG.afterTaxAsset 원 단위 값


@pytest.mark.parametrize("bad", ["552", None, 552.5])
async def test_non_integer_calc_run_id_is_never_reported_as_recalc_result(http, spring, bad):
    body = {**example("success-response.json"), "calcRunId": bad}
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly()), text_resp("RECALC_RESULT")])
    try:
        resp = await make_agent(claude, http).run(question())
    except AgentError:
        return   # 요청 실패로 올려도 된다
    assert resp.message_type != "RECALC_RESULT"
    assert resp.calc_run_id is None and resp.condition_changes is None


async def test_calc_run_id_missing_key_is_never_reported_as_recalc_result(http, spring):
    body = {k: v for k, v in example("success-response.json").items() if k != "calcRunId"}
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly()), text_resp("RECALC_RESULT")])
    try:
        resp = await make_agent(claude, http).run(question())
    except AgentError:
        return
    assert resp.message_type != "RECALC_RESULT" and resp.calc_run_id is None


HISTORY_WITH_PERIOD = [
    {"role": "user", "content": "기간을 5년으로 하면?"},
    {"role": "assistant", "content": "기간 5년이면 이렇게 돼요."},
]
SELECTED_KEYS = {"calcRunId", "display", "diff", "conditions", "current", "changed"}


async def test_tool_result_keys_when_carried_condition_is_dropped_add_only_exclusion_notice(http, spring):
    """history에서 이어받은 조건을 뺀 성공 경로(#22)의 최상위 키: 추린 키 + excludedConditions·note만 더해진다.
    나머지는 AC-8 그대로여야 한다(원 단위 값·추리기 전 키가 새지 않음)."""
    body = example("success-response.json")
    spring.mock(return_value=httpx.Response(200, json=body))
    both = {**NULLS, "monthly_saving_amount": 600_000, "target_period_months": 60}   # 기간은 앞 턴 값
    claude = FakeClaude([tool_resp(both), text_resp("RECALC_RESULT")])
    resp = await make_agent(claude, http).run(
        question("월 저축액을 60만원으로 바꾸면?", history=HISTORY_WITH_PERIOD)
    )

    assert (resp.message_type, resp.calc_run_id) == ("RECALC_RESULT", body["calcRunId"])
    sent = json.loads(tool_result(claude)["content"])
    assert {"display", "diff", "conditions", "current", "changed", "excludedConditions", "note"} <= set(sent)
    assert set(sent) <= SELECTED_KEYS | {"excludedConditions", "note"}
    assert sent["excludedConditions"] == ["target_period_months"]
    assert isinstance(sent["note"], str) and sent["note"]
    assert sent["display"] == body["display"] and sent["diff"] == body["diff"]
    for side in ("current", "changed"):
        assert set(sent[side]) <= {"baselineScenario", "scenarios", "assumptions"}

    def keys(node: Any) -> set[str]:
        if isinstance(node, dict):
            return set(node) | {k for v in node.values() for k in keys(v)}
        if isinstance(node, list):
            return {k for v in node for k in keys(v)}
        return set()

    outside_display = {k: v for k, v in sent.items() if k != "display"}
    assert not RAW_AMOUNT_KEYS & keys(outside_display)


async def test_tool_result_has_no_exclusion_notice_when_nothing_is_dropped_even_with_history(http, spring):
    """history가 있어도 이번 발화에 말한 값만 보냈으면 excludedConditions·note는 없다(AC-8 키 집합 그대로)."""
    spring.mock(return_value=httpx.Response(200, json=example("success-response.json")))
    claude = FakeClaude([tool_resp({**NULLS, "target_period_months": 60}), text_resp("RECALC_RESULT")])
    await make_agent(claude, http).run(question("기간도 5년으로 해 줘", history=HISTORY_WITH_PERIOD))

    sent = json.loads(tool_result(claude)["content"])
    assert set(sent) <= SELECTED_KEYS
    assert "excludedConditions" not in sent and "note" not in sent


# ---- AC-9(전환기): display가 비었거나 있는 시나리오의 afterTaxAsset 키가 없으면 CALC_DISPLAY_MISSING ----

def without_display_keys(*keys: str) -> dict[str, Any]:
    body = example("success-response.json")
    body["display"] = {k: v for k, v in body["display"].items() if k not in keys}
    return body


def without_display() -> dict[str, Any]:
    return {k: v for k, v in example("success-response.json").items() if k != "display"}


@pytest.mark.parametrize(
    "body",
    [
        {**example("success-response.json"), "display": {}},
        without_display(),
        without_display_keys("current.AVG.afterTaxAsset"),
        without_display_keys("changed.BAD.afterTaxAsset"),
        without_display_keys("changed.GOOD.afterTaxAsset"),
    ],
    ids=["empty-display", "no-display-key", "current-avg-missing", "changed-bad-missing", "changed-good-missing"],
)
async def test_missing_display_fails_with_calc_display_missing(http, spring, body):
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly())])
    with pytest.raises(AgentError) as caught:
        await make_agent(claude, http).run(question())
    assert caught.value.code == "CALC_DISPLAY_MISSING"
    assert len(claude.calls) == 1   # 비교를 내지 않는다


async def test_display_check_is_limited_to_after_tax_asset_keys_of_present_scenarios(http, spring):
    """gapToGoal·goalReachMonth 같은 다른 키가 없다고 실패시키지 않는다(키 규칙 C2-6)."""
    body = without_display_keys("current.AVG.gapToGoal", "changed.GOOD.goalReachMonth")
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly()), text_resp()])
    resp = await make_agent(claude, http).run(question())
    assert resp.message_type == "RECALC_RESULT"


async def test_etf_zero_response_with_only_avg_scenario_passes_display_check(http, spring):
    body = example("success-response.json")
    for side in ("current", "changed"):
        body[side]["scenarios"] = [s for s in body[side]["scenarios"] if s["scenario"] == "AVG"]
    body["diff"]["byScenario"] = [s for s in body["diff"]["byScenario"] if s["scenario"] == "AVG"]
    body["display"] = {k: v for k, v in body["display"].items() if ".GOOD." not in k and ".BAD." not in k}
    spring.mock(return_value=httpx.Response(200, json=body))
    claude = FakeClaude([tool_resp(monthly()), text_resp()])
    resp = await make_agent(claude, http).run(question())
    assert (resp.message_type, resp.calc_run_id) == ("RECALC_RESULT", body["calcRunId"])


# ---- AC-14(계약 반영 H-14): 실제로 Spring에 보낸 conditionChanges = 보고한 condition_changes ----

def to_snake(key: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", key).lower()


@pytest.mark.parametrize(
    "args",
    [
        {"monthly_saving_amount": 500_000},
        {"savings_ratio_pct": 30, "savings_rate_input_pct": 4.5},
        {"target_period_months": 60},
        {"savings_rate_input_pct": 5},
        {
            "monthly_saving_amount": 600_000, "savings_ratio_pct": 30, "savings_rate_input_pct": 4.5,
            "target_period_months": 48,
        },
    ],
)
async def test_reported_condition_changes_equal_what_was_sent_to_spring(http, spring, args):
    spring.mock(return_value=httpx.Response(200, json=example("success-response.json")))
    claude = FakeClaude([tool_resp({**NULLS, **args}), text_resp("RECALC_RESULT")])
    resp = await make_agent(claude, http).run(question("조건을 바꾸면?"))

    sent = json.loads(spring.calls.last.request.content)["conditionChanges"]
    assert {to_snake(k): v for k, v in sent.items()} == resp.condition_changes
    assert resp.condition_changes == args
    assert all(isinstance(v, type(args[to_snake(k)])) for k, v in sent.items())   # 5가 5.0이 되는 변형도 없다


async def test_reported_equals_sent_when_carried_condition_is_dropped_before_calc(http, spring):
    """history에서 이어받은 값을 도구 인자에서 뺀 뒤 보낸 값과 보고값이 같다(이미 있는 거르기와 충돌하지 않음)."""
    spring.mock(return_value=httpx.Response(200, json=example("success-response.json")))
    history = [
        {"role": "user", "content": "기간을 5년으로 하면?"},
        {"role": "assistant", "content": "기간 5년이면 이렇게 돼요."},
    ]
    both = {**NULLS, "monthly_saving_amount": 600_000, "target_period_months": 60}   # 기간은 앞 턴 값
    claude = FakeClaude([tool_resp(both), text_resp("RECALC_RESULT")])
    resp = await make_agent(claude, http).run(question("월 저축액을 60만원으로 바꾸면?", history=history))

    sent = json.loads(spring.calls.last.request.content)["conditionChanges"]
    assert {to_snake(k): v for k, v in sent.items()} == resp.condition_changes == {"monthly_saving_amount": 600_000}


async def test_calc_request_carries_member_baseline_and_internal_key(http, spring):
    spring.mock(return_value=httpx.Response(200, json=example("success-response.json")))
    claude = FakeClaude([tool_resp(monthly()), text_resp()])
    await make_agent(claude, http).run(question())
    request = spring.calls.last.request
    assert request.headers["X-Internal-Api-Key"] == "test-key"
    payload = json.loads(request.content)
    assert (payload["memberId"], payload["baselineScenario"]) == (1, "AVG")
    assert payload["conditionChanges"] == {"monthlySavingAmount": 500_000}
