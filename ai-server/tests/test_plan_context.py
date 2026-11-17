"""Issue #35 수용 테스트(1/2): planContext 받기·display 필터·모델 입력·fixture(계약 C1a-8, C1a-13).

네트워크 없이 `docs/api/examples/plan-context/`의 fixture만 읽는다. 구현 세부(private 함수)에 기대지 않고
`ChatRequest` 검증, `build_messages`가 만든 `<plan_context>` 입력, `/chat` 응답 코드만 본다.
`make_request`(C1a-13)와 fake는 test_explain.py가 가져다 쓴다.
"""

import copy
import json
import logging
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock
from fastapi.testclient import TestClient

import app.schemas as schemas
from app.api.deps import get_agent
from app.core.config import get_settings
from app.core.copy import load_copy
from app.llm.agent import ChatAgent, build_messages
from app.llm.prompts import SYSTEM_PROMPT
from app.main import app
from app.schemas import ApiModel, ChatRequest, ChatResponse, DocType, RetrievedChunk
from tests.fakes import FakeCalc

EXAMPLES = Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "plan-context"
COPY = load_copy()

# 모델 입력에 계정 식별 정보가 없는지 보려고 흔한 숫자가 아닌 값을 쓴다(fixture의 memberId 7과 겹치지 않게)
MEMBER_ID = 987_654_321
MESSAGE_ID = 5_550_123

# 계약 C1a-8 허용 패턴. 테스트가 기대값을 만들 때만 쓴다(구현의 상수에 의존하지 않는다)
DISPLAY_PATTERN = (
    r"(goalAmount|calcStartMonth|targetMonth|(GOOD|AVG|BAD)\.(afterTaxAsset|principal|afterTaxGain|gapToGoal|goalReachMonth)"
    r"|conditions\.(heldAssets|monthlySavingAmount|targetPeriodMonths|savingsRatioPct|savingsRateInputPct)"
    r"|assumptions\.(savingsRateBaseMonth|sp500BaseDate|taxRatePct))"
)

PLAN_FIXTURES = [
    "plan-context-basic.json",
    "plan-context-etf0.json",
    "plan-context-exceeded.json",
    "plan-context-achieved.json",
    "plan-context-zero-held.json",
    "plan-context-polluted.json",
    "plan-context-display-missing.json",
]
CHAT_REQUEST_FIXTURES = [
    "chat-request-explain.json",
    "chat-request-question-no-plan.json",
    "chat-request-question-with-plan.json",
    "chat-request-explain-mismatch.json",
    "chat-request-explain-null.json",
    "chat-request-explain-exceeded.json",
]
MODEL_INPUT_FIXTURE = "model-input-basic.json"
# 모델 입력을 비교할 수 있는 planContext(display 요구 키를 모두 가진 것)
VALID_PLAN_FIXTURES = [f for f in PLAN_FIXTURES if f != "plan-context-display-missing.json"]


# ---- fixture·헬퍼(test_explain.py도 쓴다) ----

def load(name: str) -> Any:
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


def make_body(plan_context: Any, mode: str, calc_run_id: int | None = None, **kw: Any) -> dict[str, Any]:
    """planContext 객체를 `/chat` 요청 본문(camelCase JSON)으로 감싼다."""
    body: dict[str, Any] = {
        "memberId": MEMBER_ID,
        "messageId": MESSAGE_ID,
        "mode": mode,
        "text": kw.pop("text", "목표까지 얼마나 모자라요?" if mode == "QUESTION" else None),
        "baselineScenario": "AVG",
        "calcRunId": calc_run_id,
        "planContext": plan_context,
        "history": [],
    }
    return {**body, **kw}


def make_request(plan_context: Any, mode: str, calc_run_id: int | None = None, **kw: Any) -> ChatRequest:
    """계약 C1a-13 헬퍼. planContext 객체를 `ChatRequest`로 감싼다."""
    return ChatRequest.model_validate(make_body(plan_context, mode, calc_run_id, **kw))


def text_resp(content: str = "설명이에요", message_type: str = "EXPLANATION") -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


class FakeClaude:
    def __init__(self, responses: list[Any] | None = None):
        self.responses = list(responses or [])
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


def make_agent(claude: FakeClaude) -> ChatAgent:
    return ChatAgent(claude, FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def user_content(req: ChatRequest) -> str:
    content = build_messages(req)[-1]["content"]
    assert isinstance(content, str)
    return content


def model_input(req: ChatRequest) -> Any:
    """이번 요청 user 메시지의 `<plan_context>…</plan_context>` 안을 JSON으로 읽는다."""
    found = re.findall(r"<plan_context>(.*?)</plan_context>", user_content(req), flags=re.DOTALL)
    assert found, "user 메시지에 <plan_context> 태그가 없다"
    return json.loads(found[-1])


def expected_model_input(plan: dict[str, Any]) -> dict[str, Any]:
    """fixture에서 계약 C1a-8 '모델에 넣는 것' 표대로 만든 기대값. 문구는 문구표에서 채운다."""
    assumptions = plan["result"]["assumptions"]
    out_assumptions: dict[str, Any] = {
        "isDefaultSavingsRate": assumptions["isDefaultSavingsRate"],
        "excludedNotes": [COPY.get(f"excluded.{code}") for code in assumptions["excludedItems"] or []],
    }
    if assumptions["scenarioMethod"]:
        out_assumptions["scenarioMethodNote"] = COPY.get(f"scenarioMethod.{assumptions['scenarioMethod']}")
    return {
        "result": {
            "status": plan["result"]["status"],
            "scenarios": [{"scenario": s["scenario"], "isAchieved": s["isAchieved"]} for s in plan["result"]["scenarios"]],
            "assumptions": out_assumptions,
        },
        "display": {k: v for k, v in plan["display"].items() if isinstance(v, str) and re.fullmatch(DISPLAY_PATTERN, k)},
    }


def without(plan: dict[str, Any], *display_keys: str) -> dict[str, Any]:
    """display에서 키를 뺀 planContext 복사본."""
    out = copy.deepcopy(plan)
    for key in display_keys:
        del out["display"][key]
    return out


class StubAgent:
    """`/chat` 라우터까지만 확인할 때 쓰는 대역. 스키마 검증(422)을 통과한 요청만 여기 닿는다."""

    def __init__(self) -> None:
        self.requests: list[ChatRequest] = []

    async def run(self, req: ChatRequest) -> ChatResponse:
        self.requests.append(req)
        return ChatResponse(message_type="ANSWER", content="ok", baseline_scenario=req.baseline_scenario)


def post_chat(body: dict[str, Any], agent: Any) -> Any:
    app.dependency_overrides[get_agent] = lambda: agent
    try:
        with TestClient(app) as client:
            return client.post(
                "/chat", headers={"X-Internal-Api-Key": get_settings().internal_api_key.get_secret_value()}, json=body
            )
    finally:
        app.dependency_overrides.clear()


# ---- AC-1: 받기(PlanContext 모델, 규칙 밖 필드 무시, fixture 수신) ----

def test_ac1_plan_context_is_api_model_that_ignores_unknown_fields():
    plan_context = getattr(schemas, "PlanContext", None)
    assert plan_context is not None and issubclass(plan_context, ApiModel)
    assert plan_context.model_config.get("extra") in (None, "ignore")   # 규칙 밖 필드는 오류가 아니라 무시(C1a-8)


@pytest.mark.parametrize("name", PLAN_FIXTURES)
def test_ac1_plan_context_fixtures_are_received_as_plan_context(name: str):
    plan = load(name)
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])
    assert isinstance(req.plan_context, schemas.PlanContext)
    assert req.plan_context.calc_run_id == plan["calcRunId"]
    assert req.plan_context.result.status == plan["result"]["status"]


@pytest.mark.parametrize("name", CHAT_REQUEST_FIXTURES)
def test_ac1_chat_request_fixtures_are_received(name: str):
    raw = load(name)
    req = ChatRequest.model_validate(raw)
    assert req.member_id == raw["memberId"]
    if raw["planContext"] is None:
        assert req.plan_context is None
    else:
        assert isinstance(req.plan_context, schemas.PlanContext)


def test_ac17_every_fixture_file_is_used_by_these_tests():
    """fixture가 늘면 테스트도 늘어야 한다(G-4: ai-server용 14개 전부)."""
    used = {*PLAN_FIXTURES, *CHAT_REQUEST_FIXTURES, MODEL_INPUT_FIXTURE}
    assert {p.name for p in EXAMPLES.glob("*.json")} == used


# ---- AC-2: 필수 필드가 빠지면 FastAPI 기본 422 ----

def _drop(path: str) -> Any:
    keys = path.split(".")

    def mutate(plan: dict[str, Any]) -> None:
        node: Any = plan
        for key in keys[:-1]:
            node = node[int(key)] if key.isdigit() else node[key]
        del node[keys[-1]]

    return mutate


def _set(path: str, value: Any) -> Any:
    keys = path.split(".")

    def mutate(plan: dict[str, Any]) -> None:
        node: Any = plan
        for key in keys[:-1]:
            node = node[int(key)] if key.isdigit() else node[key]
        node[keys[-1]] = value

    return mutate


INVALID_PLAN_CONTEXTS = {
    "result 없음": _drop("result"),
    "result null": _set("result", None),
    "status 없음": _drop("result.status"),
    "status 모르는 값": _set("result.status", "FAILED"),
    "scenarios 없음": _drop("result.scenarios"),
    "scenarios null": _set("result.scenarios", None),
    "scenario 이름 없음": _drop("result.scenarios.0.scenario"),
    "scenario 모르는 이름": _set("result.scenarios.0.scenario", "BEST"),
    "isAchieved 없음": _drop("result.scenarios.1.isAchieved"),
    "assumptions 없음": _drop("result.assumptions"),
    "isDefaultSavingsRate 없음": _drop("result.assumptions.isDefaultSavingsRate"),
    "scenarioMethod 키 없음": _drop("result.assumptions.scenarioMethod"),
    "excludedItems 키 없음": _drop("result.assumptions.excludedItems"),
    "display 없음": _drop("display"),
    "display null": _set("display", None),
    "display가 배열": _set("display", ["goalAmount"]),
    "display가 문자열": _set("display", "goalAmount"),
    "planContext가 객체 아님": lambda plan: plan.clear(),   # 아래에서 문자열로 바꾼다
}


@pytest.mark.parametrize("case", INVALID_PLAN_CONTEXTS)
def test_ac2_missing_required_fields_or_bad_status_is_fastapi_422(case: str):
    plan = load("plan-context-basic.json")
    INVALID_PLAN_CONTEXTS[case](plan)
    body = make_body("not-an-object" if case == "planContext가 객체 아님" else plan, "EXPLAIN", 540)
    agent = StubAgent()

    r = post_chat(body, agent)

    assert r.status_code == 422
    assert isinstance(r.json()["detail"], list) and r.json()["detail"]   # FastAPI 기본 본문 {detail: [...]}
    assert agent.requests == []


@pytest.mark.parametrize("name", ["plan-context-etf0.json", "plan-context-exceeded.json"])
def test_ac2_null_scenario_method_and_empty_scenarios_are_valid(name: str):
    """키는 필수지만 값은 null 가능(ETF 0%), 초과면 scenarios는 빈 배열이다."""
    agent = StubAgent()
    r = post_chat(make_body(load(name), "QUESTION"), agent)
    assert r.status_code == 200 and len(agent.requests) == 1
    assert isinstance(agent.requests[0].plan_context, schemas.PlanContext)


# ---- AC-3: display 필터(규칙 밖 키·문자열 아닌 값은 조용히 버림) ----

def test_ac3_polluted_display_is_filtered_and_model_input_equals_expected():
    plan = load("plan-context-polluted.json")
    req = make_request(plan, "EXPLAIN", 540)

    assert req.plan_context is not None
    assert req.plan_context.display == load("plan-context-basic.json")["display"]   # 받는 시점에 이미 걸러져 있다
    expected = load(MODEL_INPUT_FIXTURE)
    expected["result"]["assumptions"]["excludedNotes"] = [
        COPY.get(f"excluded.{c}") for c in plan["result"]["assumptions"]["excludedItems"]
    ]
    expected["result"]["assumptions"]["scenarioMethodNote"] = COPY.get("scenarioMethod.ROLLING_DISTRIBUTION")
    assert model_input(req) == expected
    text = user_content(req)
    for leaked in ("loginId", "demo01", "current.AVG.afterTaxAsset", "AVG.annualReturnPct", "202911"):
        assert leaked not in text


def test_ac3_allowed_pattern_passes_and_everything_else_is_dropped():
    plan = load("plan-context-basic.json")
    dropped = {
        "xAVG.afterTaxAsset": "x", "AVG.afterTaxAssetX": "x", "avg.afterTaxAsset": "x", "BAD.annualReturnPct": "연 1.1%",
        "GOOD.requiredMonthlySaving": "x", "conditions.goalAmount": "x", "assumptions.savingsRatePct": "x",
        "diff.AVG.afterTaxAssetDelta": "x", "changed.AVG.afterTaxAsset": "x", "conditions.current.heldAssets": "x",
        "goalAmount ": "x", "result.status": "x", "memberId": "7",
        "AVG.goalReachMonth": None, "GOOD.goalReachMonth": 202905,   # 값이 문자열이 아니면 버린다
    }
    plan["display"].update(dropped)
    plan["display"]["assumptions.sp500BaseDate"] = 20261031   # 선택 키라도 숫자면 버린다(오류 아님)
    plan["display"]["BAD.goalReachMonth"] = "2029년 11월"      # 패턴에 맞는 문자열은 통과한다

    shown = model_input(make_request(plan, "QUESTION"))["display"]

    expected = load("plan-context-basic.json")["display"]
    del expected["assumptions.sp500BaseDate"]
    expected["BAD.goalReachMonth"] = "2029년 11월"
    assert shown == expected
    assert shown["AVG.afterTaxAsset"] == "약 1,712만원"   # 허용 패턴의 비교 대상이 아닌 정상 키


# ---- AC-4: 모델 입력 = 계약 표 ----

def test_ac4_model_input_basic_equals_fixture_plus_copy_notes():
    expected = load(MODEL_INPUT_FIXTURE)
    assert "excludedNotes" not in expected["result"]["assumptions"]   # 문구 키는 fixture에 없고 테스트가 채운다
    expected["result"]["assumptions"]["excludedNotes"] = [
        COPY.get(f"excluded.{c}") for c in load("plan-context-basic.json")["result"]["assumptions"]["excludedItems"]
    ]
    expected["result"]["assumptions"]["scenarioMethodNote"] = COPY.get("scenarioMethod.ROLLING_DISTRIBUTION")

    req = make_request(load("plan-context-basic.json"), "EXPLAIN", 540)

    assert model_input(req) == expected
    assert model_input(req)["result"]["assumptions"]["excludedNotes"][1] == "환율 변동은 반영하지 않았어요"   # D-027


@pytest.mark.parametrize("name", VALID_PLAN_FIXTURES)
def test_ac4_model_input_of_every_valid_fixture_follows_the_table(name: str):
    plan = load(name)
    shown = model_input(make_request(plan, "QUESTION"))

    assert shown == expected_model_input(plan)
    assert set(shown) == {"result", "display"}
    assert set(shown["result"]) == {"status", "scenarios", "assumptions"}
    assert set(shown["result"]["assumptions"]) <= {"isDefaultSavingsRate", "scenarioMethodNote", "excludedNotes"}


@pytest.mark.parametrize("name", VALID_PLAN_FIXTURES)
def test_ac4_no_raw_numbers_ids_or_account_info_reach_the_model(name: str):
    plan = load(name)
    messages = json.dumps(build_messages(make_request(plan, "EXPLAIN", plan["calcRunId"])), ensure_ascii=False)
    for raw in (
        "calcRunId", "dataVersions", "requiredMonthlySaving", "baselineScenario", "annualReturnPct", "savingsRatePct",
        "monthlyPoints", "engineVersion", "VersionId", "\"2026-11\"", "\"2029-11\"", "18450000", "20000000", "15800000",
        "taxNote",
    ):
        assert raw not in messages, raw
    for everything in (messages, messages + SYSTEM_PROMPT):
        assert "memberId" not in everything and "member_id" not in everything   # D-030
        assert str(MEMBER_ID) not in everything and str(MESSAGE_ID) not in everything
        assert "loginId" not in everything and "demo01" not in everything
        assert COPY.get("tax.note") not in everything   # D-073: 단서는 코드가 응답에 붙인다. 모델 입력에 넣지 않는다


def test_ac4_scenario_method_note_key_is_absent_when_method_is_null():
    plan = load("plan-context-etf0.json")
    shown = model_input(make_request(plan, "QUESTION"))
    assert shown == expected_model_input(plan)
    assert "scenarioMethodNote" not in shown["result"]["assumptions"]
    assert [s["scenario"] for s in shown["result"]["scenarios"]] == ["AVG"]   # ETF 0%: 3구간을 말하지 않는다
    assert all(not k.startswith(("GOOD.", "BAD.")) for k in shown["display"])


def test_ac4_null_excluded_items_gives_no_notes():
    plan = load("plan-context-basic.json")
    plan["result"]["assumptions"]["excludedItems"] = None
    assumptions = model_input(make_request(plan, "QUESTION"))["result"]["assumptions"]
    assert set(assumptions) <= {"isDefaultSavingsRate", "scenarioMethodNote", "excludedNotes"}   # 코드가 새어 나가지 않는다
    assert assumptions.get("excludedNotes", []) == []


def test_ac4_zero_held_and_under_one_man_display_are_quoted_verbatim():
    plan = load("plan-context-zero-held.json")
    shown = model_input(make_request(plan, "QUESTION"))
    assert shown == expected_model_input(plan)
    shown = shown["display"]
    assert shown["conditions.heldAssets"] == "0원" and shown["AVG.afterTaxGain"] == "1만원 미만"   # D-061


# ---- AC-5: 직렬화 ----

def test_ac5_model_input_is_camel_case_and_not_ascii_escaped():
    req = make_request(load("plan-context-basic.json"), "EXPLAIN", 540)
    content = user_content(req)

    assert "약 1,712만원" in content and "\\uc57d" not in content   # ensure_ascii=False
    shown = model_input(req)
    assert shown == expected_model_input(load("plan-context-basic.json"))
    assert "isAchieved" in shown["result"]["scenarios"][0] and "isDefaultSavingsRate" in shown["result"]["assumptions"]
    for snake in ("is_achieved", "is_default_savings_rate", "excluded_notes", "scenario_method_note", "calc_run_id"):
        assert snake not in content


# ---- AC-7: 계획 없음 ----

def test_ac7_no_plan_shows_null_in_plan_context_tag():
    req = ChatRequest.model_validate(load("chat-request-question-no-plan.json"))
    assert req.plan_context is None
    assert "<plan_context>null</plan_context>" in user_content(req)


# ---- AC-13: 문구표에 문장이 없는 excludedItems 코드 ----

def test_ac13_unknown_excluded_code_is_dropped_with_warning_and_never_reaches_model(caplog: pytest.LogCaptureFixture):
    plan = load("plan-context-basic.json")
    plan["result"]["assumptions"]["excludedItems"] = ["ETF_TOTAL_EXPENSE", "TOTALLY_UNKNOWN_CODE", "EXCHANGE_RATE"]
    caplog.set_level(logging.DEBUG)

    req = make_request(plan, "EXPLAIN", 540)
    shown = model_input(req)

    notes = [COPY.get("excluded.ETF_TOTAL_EXPENSE"), COPY.get("excluded.EXCHANGE_RATE")]
    assert shown["result"]["assumptions"]["excludedNotes"] == notes
    assert "TOTALLY_UNKNOWN_CODE" not in user_content(req)
    assert any(r.levelno >= logging.WARNING for r in caplog.records)
    assert "약 1,712만원" not in caplog.text   # 경고에도 planContext 본문은 없다
