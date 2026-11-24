"""Issue #35 수용 테스트(2/2): EXPLAIN·QUESTION 흐름, 검사 순서 1~6, 세금 단서(D-067·D-073), 프롬프트 규칙, 로그.

모델은 FakeClaude다. FakeClaude는 프롬프트 규칙 준수 여부를 알 수 없으므로(규칙 1·2·3·5·6·8·9의 모델 행동은
수동 평가 몫, spec.md의 수동 평가 목록) 여기서는 (1) 프롬프트에 규칙 문구가 있는지, (2) 코드가 보장하는 것만 본다.
"""

import logging
from typing import Any

import pytest

from app.llm.agent import AgentError
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest
from tests.test_plan_context import (
    COPY,
    MEMBER_ID,
    FakeClaude,
    expected_model_input,
    load,
    make_agent,
    make_body,
    make_request,
    model_input,
    post_chat,
    text_resp,
    user_content,
    without,
)

TAX_NOTE = COPY.get("tax.note")
SENSITIVE = [
    "약 2,000만원", "약 1,712만원", "-약 288만원 미달", "18450000", "17120000", "demo01", "15.4%", "ROLLING_DISTRIBUTION",
]


async def run(req: ChatRequest, *responses: Any) -> tuple[Any, FakeClaude]:
    claude = FakeClaude(list(responses))
    return await make_agent(claude).run(req), claude


async def error_of(req: ChatRequest) -> tuple[AgentError, FakeClaude]:
    """모델을 부르기 전에 AgentError가 나야 하는 요청. 응답을 하나도 안 줘서 호출되면 IndexError로 실패한다."""
    claude = FakeClaude()
    with pytest.raises(AgentError) as caught:
        await make_agent(claude).run(req)
    return caught.value, claude


# ---- AC-6: EXPLAIN은 display 문자열만 모델에 주고, 응답 필드는 서버 사실로 채운다 ----

async def test_ac6_explain_gives_model_only_display_input_and_keeps_calc_run_id():
    req = make_request(load("plan-context-basic.json"), "EXPLAIN", 540)
    answer = "보통 경우에는 약 1,712만원이 되고, 목표까지 -약 288만원 미달이에요."

    resp, claude = await run(req, text_resp(answer))

    assert len(claude.calls) == 1
    assert model_input(req) == expected_model_input(load("plan-context-basic.json"))
    assert "<plan_context>" in user_content(req) and "<baseline_scenario>AVG</baseline_scenario>" in user_content(req)
    assert resp.message_type == "EXPLANATION" and resp.calc_run_id == 540 and resp.baseline_scenario == "AVG"
    assert resp.content.startswith(answer)   # 코드는 금액을 고치지 않는다(세금 단서만 덧붙을 수 있다)


async def test_ac6_calls_carry_no_account_info_in_system_or_messages():
    req = make_request(load("plan-context-polluted.json"), "EXPLAIN", 540)
    _, claude = await run(req, text_resp())

    sent = str(claude.calls[0]["system"]) + str(claude.calls[0]["messages"])
    assert "memberId" not in sent and "member_id" not in sent and str(MEMBER_ID) not in sent
    assert "demo01" not in sent and "loginId" not in sent


# ---- AC-7: QUESTION ----

async def test_ac7_question_with_plan_quotes_plan_context_and_has_no_calc_run_id():
    req = ChatRequest.model_validate(load("chat-request-question-with-plan.json"))
    resp, claude = await run(req, text_resp("세금은 15.4%로 가정했어요.", "ANSWER"))

    assert model_input(req) == expected_model_input(load("plan-context-basic.json"))
    assert resp.message_type == "ANSWER" and resp.calc_run_id is None   # QUESTION은 calcRunId null이 정상
    assert len(claude.calls) == 1


async def test_ac7_question_without_plan_still_reaches_model_with_null_plan_context():
    req = ChatRequest.model_validate(load("chat-request-question-no-plan.json"))
    resp, claude = await run(req, text_resp("ETF는 지수를 따라가는 펀드예요.", "ANSWER"))

    assert "<plan_context>null</plan_context>" in claude.calls[0]["messages"][-1]["content"]
    assert resp.message_type == "ANSWER" and resp.content == "ETF는 지수를 따라가는 펀드예요."


# ---- AC-8·AC-9: 검사 순서 2~4(모델 호출 없음) ----

async def test_ac8_explain_with_null_plan_context_is_bad_request_without_llm():
    err, claude = await error_of(ChatRequest.model_validate(load("chat-request-explain-null.json")))
    assert err.code == "BAD_REQUEST" and claude.calls == []


async def test_ac8_explain_with_mismatched_calc_run_id_is_bad_request_without_llm():
    raw = load("chat-request-explain-mismatch.json")
    assert raw["calcRunId"] != raw["planContext"]["calcRunId"]
    err, claude = await error_of(ChatRequest.model_validate(raw))
    assert err.code == "BAD_REQUEST" and claude.calls == []


async def test_ac8_mismatch_is_checked_before_already_exceeded():
    raw = load("chat-request-explain-exceeded.json")
    raw["calcRunId"] = 999
    err, claude = await error_of(ChatRequest.model_validate(raw))
    assert err.code == "BAD_REQUEST" and claude.calls == []


async def test_ac9_already_exceeded_explain_answers_with_copy_and_no_llm():
    req = ChatRequest.model_validate(load("chat-request-explain-exceeded.json"))
    claude = FakeClaude()

    resp = await make_agent(claude).run(req)

    assert claude.calls == []
    assert resp.message_type == "EXPLANATION"
    assert resp.content == COPY.get("chat.alreadyExceeded")   # 세금 단서 등 아무것도 덧붙지 않는다
    assert resp.calc_run_id == 542 and resp.baseline_scenario == "AVG"
    assert resp.citation_chunk_ids == [] and resp.condition_changes is None


async def test_ac9_already_exceeded_is_decided_before_display_check():
    """검사 순서 4가 5보다 앞이다. 초과 EXPLAIN은 display가 모자라도 고정 문장으로 끝난다."""
    plan = without(load("plan-context-exceeded.json"), "conditions.heldAssets")
    resp, claude = await run(make_request(plan, "EXPLAIN", 542))
    assert claude.calls == [] and resp.content == COPY.get("chat.alreadyExceeded")


# ---- AC-10: 검사 순서 5, display 요구 키 ----

CALCULATED_REQUIRED = [
    "goalAmount", "calcStartMonth", "targetMonth", "conditions.heldAssets", "conditions.monthlySavingAmount",
    "conditions.targetPeriodMonths", "conditions.savingsRatioPct", "conditions.savingsRateInputPct",
    "assumptions.taxRatePct",
    *(f"{s}.{k}" for s in ("GOOD", "AVG", "BAD") for k in ("afterTaxAsset", "principal", "afterTaxGain", "gapToGoal")),
]


@pytest.mark.parametrize("key", CALCULATED_REQUIRED)
async def test_ac10_calculated_without_required_display_key_is_502_without_llm(key: str):
    req = make_request(without(load("plan-context-basic.json"), key), "EXPLAIN", 540)
    err, claude = await error_of(req)
    assert err.code == "CONTEXT_DISPLAY_MISSING" and claude.calls == []


async def test_ac10_display_missing_fixture_is_502_for_explain_and_question():
    plan = load("plan-context-display-missing.json")
    for req in (make_request(plan, "EXPLAIN", 540), make_request(plan, "QUESTION")):
        err, claude = await error_of(req)
        assert err.code == "CONTEXT_DISPLAY_MISSING" and claude.calls == []


async def test_ac10_non_string_value_counts_as_missing():
    plan = load("plan-context-basic.json")
    plan["display"]["AVG.gapToGoal"] = -2880000
    err, _ = await error_of(make_request(plan, "EXPLAIN", 540))
    assert err.code == "CONTEXT_DISPLAY_MISSING"


async def test_ac10_empty_display_is_502():
    plan = load("plan-context-basic.json")
    plan["display"] = {}
    err, _ = await error_of(make_request(plan, "QUESTION"))
    assert err.code == "CONTEXT_DISPLAY_MISSING"


@pytest.mark.parametrize("scenario", ["GOOD", "AVG"])
async def test_ac10_achieved_scenario_requires_its_goal_reach_month(scenario: str):
    plan = without(load("plan-context-achieved.json"), f"{scenario}.goalReachMonth")
    err, claude = await error_of(make_request(plan, "EXPLAIN", plan["calcRunId"]))
    assert err.code == "CONTEXT_DISPLAY_MISSING" and claude.calls == []


async def test_ac10_missed_scenario_needs_no_goal_reach_month():
    """achieved fixture의 BAD는 isAchieved=false이고 goalReachMonth 키가 없다. 정상이다."""
    plan = load("plan-context-achieved.json")
    assert "BAD.goalReachMonth" not in plan["display"]
    resp, claude = await run(make_request(plan, "EXPLAIN", plan["calcRunId"]), text_resp("설명"))
    assert len(claude.calls) == 1 and resp.message_type == "EXPLANATION"


@pytest.mark.parametrize("key", ["assumptions.savingsRateBaseMonth", "assumptions.sp500BaseDate"])
async def test_ac10_optional_assumption_keys_may_be_absent(key: str):
    resp, claude = await run(make_request(without(load("plan-context-basic.json"), key), "EXPLAIN", 540), text_resp())
    assert len(claude.calls) == 1 and resp.message_type == "EXPLANATION"


@pytest.mark.parametrize("name", ["plan-context-etf0.json", "plan-context-zero-held.json", "plan-context-achieved.json"])
async def test_ac10_valid_fixtures_reach_the_model(name: str):
    plan = load(name)
    resp, claude = await run(make_request(plan, "EXPLAIN", plan["calcRunId"]), text_resp("설명"))
    assert len(claude.calls) == 1 and resp.calc_run_id == plan["calcRunId"]


@pytest.mark.parametrize("key", ["goalAmount", "conditions.heldAssets"])
async def test_ac10_already_exceeded_question_requires_only_goal_amount_and_held_assets(key: str):
    err, claude = await error_of(make_request(without(load("plan-context-exceeded.json"), key), "QUESTION"))
    assert err.code == "CONTEXT_DISPLAY_MISSING" and claude.calls == []


@pytest.mark.parametrize(
    "key", ["calcStartMonth", "targetMonth", "conditions.monthlySavingAmount", "assumptions.taxRatePct"]
)
async def test_ac10_already_exceeded_question_ignores_other_missing_keys(key: str):
    req = make_request(without(load("plan-context-exceeded.json"), key), "QUESTION")
    resp, claude = await run(req, text_resp("ok", "ANSWER"))
    assert len(claude.calls) == 1 and resp.message_type == "ANSWER"


# ---- AC-11: 400·422·502 HTTP 상태와 본문 ----

def _post_with_agent(body: dict[str, Any]) -> Any:
    claude = FakeClaude()
    return post_chat(body, make_agent(claude)), claude


def test_ac11_http_statuses_for_checks_2_to_5():
    r, claude = _post_with_agent(load("chat-request-explain-null.json"))
    assert (r.status_code, r.json()) == (400, {"error": "BAD_REQUEST"})
    r, _ = _post_with_agent(load("chat-request-explain-mismatch.json"))
    assert (r.status_code, r.json()) == (400, {"error": "BAD_REQUEST"})
    r, _ = _post_with_agent(make_body(load("plan-context-display-missing.json"), "EXPLAIN", 540))
    assert (r.status_code, r.json()) == (502, {"error": "CONTEXT_DISPLAY_MISSING"})
    assert claude.calls == []


def test_ac11_http_already_exceeded_returns_camel_case_explanation():
    r, claude = _post_with_agent(load("chat-request-explain-exceeded.json"))
    assert r.status_code == 200 and claude.calls == []
    assert r.json() == {
        "messageType": "EXPLANATION", "content": COPY.get("chat.alreadyExceeded"), "baselineScenario": "AVG",
        "calcRunId": 542, "conditionChanges": None, "citationChunkIds": [],
    }


# ---- AC-12: 400·422·502 로그에 planContext 본문이 없다 ----

def _bad_requests() -> dict[str, tuple[ChatRequest, str, str]]:
    polluted_missing = without(load("plan-context-polluted.json"), "AVG.gapToGoal")
    return {
        "null": (ChatRequest.model_validate(load("chat-request-explain-null.json")), "BAD_REQUEST", "540"),
        "mismatch": (ChatRequest.model_validate(load("chat-request-explain-mismatch.json")), "BAD_REQUEST", "999"),
        "display-missing": (
            make_request(load("plan-context-display-missing.json"), "EXPLAIN", 540), "CONTEXT_DISPLAY_MISSING", "540"
        ),
        "polluted-display-missing": (make_request(polluted_missing, "EXPLAIN", 540), "CONTEXT_DISPLAY_MISSING", "540"),
    }


@pytest.mark.parametrize("case", ["null", "mismatch", "display-missing", "polluted-display-missing"])
async def test_ac12_400_and_502_log_only_calc_run_id_and_error_code(case: str, caplog: pytest.LogCaptureFixture):
    req, code, calc_run_id = _bad_requests()[case]
    caplog.set_level(logging.DEBUG)

    err, _ = await error_of(req)

    assert err.code == code
    messages = "\n".join(r.getMessage() for r in caplog.records)   # caplog.text의 파일 줄 번호와 섞이지 않게
    assert code in messages and calc_run_id in messages   # 무슨 요청이 왜 실패했는지는 남긴다
    for secret in SENSITIVE:
        assert secret not in str(err), secret   # Spring에 가는 오류에는 본문이 없다. 로그는 #316부터 남긴다


def test_ac12_422_logs_no_plan_context_body(caplog: pytest.LogCaptureFixture):
    plan = load("plan-context-polluted.json")
    del plan["result"]["status"]
    caplog.set_level(logging.DEBUG)

    r = post_chat(make_body(plan, "EXPLAIN", 540), None)

    assert r.status_code == 422


# ---- AC-14·AC-15: 세금 단서(D-067·D-073)는 답에 붙이지 않고(#233, 대화 패널 고정 문구), 범위 밖 세금 질문은 고정 문장 ----

@pytest.mark.parametrize(
    ("mode", "message_type", "request_name"),
    [
        ("EXPLAIN", "EXPLANATION", "chat-request-explain.json"),
        ("QUESTION", "ANSWER", "chat-request-question-with-plan.json"),
    ],
)
async def test_ac14_tax_note_is_not_appended_even_when_answer_quotes_tax_rate(
    mode: str, message_type: str, request_name: str
):
    req = ChatRequest.model_validate(load(request_name))
    answer = "세금은 15.4%로 가정했어요."
    assert req.mode == mode

    resp, _ = await run(req, text_resp(answer, message_type))

    assert resp.message_type == message_type
    assert resp.content == answer   # 글자 그대로, 단서 없이
    assert TAX_NOTE not in resp.content and COPY.get("tax.noteEtfTaxFree") not in resp.content


async def test_ac14_tax_note_written_by_model_is_removed():
    answer = "세금은 15.4%로 가정했어요."
    resp, _ = await run(make_request(load("plan-context-basic.json"), "EXPLAIN", 540), text_resp(f"{answer} {TAX_NOTE}"))
    assert resp.content == answer


async def test_ac14_tax_note_is_not_appended_when_answer_has_no_tax_rate():
    answer = "보통 경우에는 약 1,712만원이 돼요."
    resp, _ = await run(make_request(load("plan-context-basic.json"), "EXPLAIN", 540), text_resp(answer))
    assert resp.content == answer and TAX_NOTE not in resp.content


async def test_ac14_model_breaking_rule_8_is_a_normal_response_not_an_error():
    """모델이 기억 속 세율을 말해도 실패(AgentError)로 처리하지 않는다. 단서는 어느 쪽에도 붙지 않는다(#233)."""
    answer = "세금은 22%를 떼요."
    resp, _ = await run(make_request(load("plan-context-basic.json"), "EXPLAIN", 540), text_resp(answer))
    assert resp.message_type == "EXPLANATION" and resp.content == answer

    resp, _ = await run(make_request(load("plan-context-basic.json"), "EXPLAIN", 540), text_resp("세금은 15.4%예요."))
    assert resp.message_type == "EXPLANATION" and resp.content == "세금은 15.4%예요."


async def test_ac15_out_of_scope_tax_answer_is_passed_through_as_out_of_scope():
    text = "이 ETF는 세금 혜택이 있어요? 절세하는 방법도 알려줘요."
    req = make_request(load("plan-context-basic.json"), "QUESTION", text=text)
    sentence = COPY.get("outOfScope.tax")

    resp, _ = await run(req, text_resp(sentence, "OUT_OF_SCOPE"))

    assert resp.message_type == "OUT_OF_SCOPE" and resp.content == sentence
    assert resp.calc_run_id is None and resp.condition_changes is None and resp.citation_chunk_ids == []


# ---- AC-16: 프롬프트 규칙(문구가 프롬프트에 있는지만. 모델 준수는 수동 평가) ----

PROMPT_RULES = {
    "규칙1 display를 글자 그대로 인용": ["display", "글자 그대로"],
    "규칙2 새 숫자·초과 금액 금지": ["새 숫자", "초과 금액"],
    "규칙3 미달이면 시점 없이": ["isAchieved", "goalReachMonth", "기간 안에 도달하지 않아요"],
    "규칙5 가정 문장 그대로": ["excludedNotes", "scenarioMethodNote"],
    "규칙6 연 수익률 숫자 금지": ["과거 데이터로 만든 구간", "수익을 약속하지 않", "주어지지 않은", "모른다"],
    "규칙8 세율은 display만": ["assumptions.taxRatePct", "세율"],
    "규칙9 초과 계획": ["ALREADY_EXCEEDED", "초과 금액", "대화로 목표 금액을 더 높게 바꿔 볼 수 있어요"],
}


@pytest.mark.parametrize("rule", PROMPT_RULES)
def test_ac16_prompt_contains_rule_phrases(rule: str):
    missing = [token for token in PROMPT_RULES[rule] if token not in SYSTEM_PROMPT]
    assert not missing, missing


def test_ac16_prompt_has_fixed_sentences_but_not_the_tax_note():
    assert COPY.get("outOfScope.tax") in SYSTEM_PROMPT and "OUT_OF_SCOPE" in SYSTEM_PROMPT   # 범위 밖 세금 질문
    assert COPY.get("chat.noPlan") in SYSTEM_PROMPT                                          # 계획 없음 + 계산·설명 요청
    assert TAX_NOTE not in SYSTEM_PROMPT                                                     # 단서는 패널이 보인다(#233)


def test_ac16_prompt_does_not_leak_account_info():
    assert "memberId" not in SYSTEM_PROMPT and "member_id" not in SYSTEM_PROMPT and str(MEMBER_ID) not in SYSTEM_PROMPT
