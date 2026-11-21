"""Issue #37(T-029): AI 응답 숫자 자동 대조 NUMBER_MISMATCH (FR-AI-02, FR-AI-04, NFR-INT-01, PR-04).

결정 기록 U-1~U-6(2026-11-18)을 따른다.
- 근거: planContext.display 값, 이번 요청의 /internal/calc 200 응답 display 값, 422 VALIDATION_FAILED의
  errors[].minDisplay·maxDisplay, 이번 요청 text(U-1, U-2). history·condition_changes 원 단위 값,
  search_documents 결과 문자열, 이전 요청의 값은 근거가 아니다.
- 대조: 아라비아 숫자로 시작하는 금액(원·만원·억)과 N년·N개월·N달, 'N년 M월' 날짜 토큰이 근거 문자열 안에
  토큰 경계로 그대로 있어야 한다. "약"·부호 생략은 허용, 단위 환산은 하지 않는다. 한글 수사 금액은 보지 않는다(U-3, U-4).
- 순서: 상품명·추천 검사(FORBIDDEN_EXPRESSION)가 먼저다(U-5). OUT_OF_SCOPE 본문은 대조하지 않는다(U-6).

check_numbers 단위 테스트와 공개 진입점 ChatAgent.run 테스트를 함께 둔다. 네트워크 없이 fake만 쓴다.
"""

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.llm.agent import AgentError, ChatAgent
from app.llm.number_check import NumberMismatch, check_numbers
from app.schemas import ChatRequest, ChatResponse, DocType, RetrievedChunk
from app.tools.calc import CalcRejected
from tests.fakes import FakeCalc
from tests.test_out_of_scope import (
    NULLS,
    PLAN_CONTEXT,
    FakeClaude,
    FakeRetriever,
    MarkerJudge,
    text_resp,
    tool_resp,
)

EXAMPLES = Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "internal-calc"
PLAN_DISPLAY: list[str] = list(PLAN_CONTEXT["display"].values())
GENERIC = "S&P500 추종 ETF"


def calc_error(name: str) -> CalcRejected:
    body = json.loads((EXAMPLES / name).read_text(encoding="utf-8"))
    return CalcRejected(body["code"], body["errors"])


def make_agent(claude: FakeClaude, calc: Any = None) -> ChatAgent:
    return ChatAgent(
        claude, calc or FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4,
        stock_judge=MarkerJudge(),
    )


def question(text: str, **kw: Any) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


def explain() -> ChatRequest:
    return ChatRequest(
        memberId=1, messageId=9, mode="EXPLAIN", calcRunId=540, baselineScenario="AVG", planContext=PLAN_CONTEXT
    )


def monthly(amount: int) -> Any:
    return tool_resp("recalculate", {**NULLS, "monthly_saving_amount": amount})


async def outcome(agent: ChatAgent, req: ChatRequest) -> ChatResponse | AgentError:
    try:
        return await agent.run(req)
    except AgentError as e:
        return e


def assert_code(result: ChatResponse | AgentError, code: str) -> None:
    assert isinstance(result, AgentError), f"거부되지 않았다: {result}"
    assert result.code == code


# ---- AC-1: 본문 금액·기간이 display 값과 같으면 통과한다 (C-1) ----

@pytest.mark.parametrize(
    "content",
    [
        "평균 시나리오에서 약 1,712만원이 모여요.",           # display 그대로
        "평균 시나리오에서 1,712만원이 모여요.",              # '약' 생략
        "목표까지 288만원 모자라요.",                          # 부호(-약 … 미달) 생략
        "월 약 30만원씩 3년 0개월 동안 모아요.",              # 금액 + 기간
        "목표 시점은 2029년 11월이에요.",                      # 'N년 M월' 날짜
        "목표 약 2,000만원 중 원금은 약 1,580만원이에요.",     # 여러 토큰이 모두 근거에 있음
    ],
)
def test_amounts_and_periods_equal_to_display_pass(content: str) -> None:
    """본문 금액·기간 토큰이 planContext display 문자열에 그대로 있으면 예외 없이 통과한다 (U-3)"""
    check_numbers(content, PLAN_DISPLAY)


async def test_explain_body_matching_plan_display_is_returned_unchanged() -> None:
    """(회귀 보호) EXPLAIN에서 display와 같은 금액만 말한 본문은 EXPLANATION으로 그대로 나간다"""
    text = "평균 시나리오에서 약 1,712만원이 모이고 목표까지 288만원 모자라요."
    resp = await make_agent(FakeClaude([text_resp("EXPLANATION", text)])).run(explain())
    assert resp.message_type == "EXPLANATION"
    assert resp.content == text


async def test_recalc_body_matching_calc_display_passes() -> None:
    """(회귀 보호) 이번 /internal/calc 응답 display에만 있는 금액(약 2,501만원)도 근거로 통과한다"""
    text = "월 50만원이면 평균 약 2,501만원이 모여 목표보다 501만원 많아요."
    claude = FakeClaude([monthly(500_000), text_resp("RECALC_RESULT", text)])
    resp = await make_agent(claude).run(question("월 50만원으로 바꾸면?", plan_context=PLAN_CONTEXT))
    assert resp.message_type == "RECALC_RESULT"
    assert resp.content == text


async def test_recalc_body_quoting_calc_diff_display_passes_and_other_amount_fails() -> None:
    """(AC-1) 계산 응답의 display.diff.* 에만 있는 금액(약 789만원 증가)도 근거로 통과하고,
    같은 요청에서 금액만 바꾸면(790만원) NUMBER_MISMATCH. diff 표시값을 근거에서 빼는 구현을 막는다"""
    ok = "평균 시나리오에서 약 789만원 증가해요."
    claude = FakeClaude([monthly(500_000), text_resp("RECALC_RESULT", ok)])
    resp = await make_agent(claude).run(question("월 50만원으로 바꾸면?"))
    assert resp.message_type == "RECALC_RESULT"
    assert resp.content == ok

    bad = FakeClaude([monthly(500_000), text_resp("RECALC_RESULT", "평균 시나리오에서 약 790만원 증가해요.")])
    assert_code(await outcome(make_agent(bad), question("월 50만원으로 바꾸면?")), "NUMBER_MISMATCH")


async def test_clarify_body_quoting_validation_max_display_passes() -> None:
    """(회귀 보호) 422 VALIDATION_FAILED의 maxDisplay(1,000만원)는 근거다 (U-2)"""
    text = "월 저축액은 1,000만원까지 넣을 수 있어요. 얼마로 할까요?"
    claude = FakeClaude([monthly(12_000_000), text_resp("CLARIFY", text)])
    calc = FakeCalc(error=calc_error("validation-failed-out-of-range-response.json"))
    resp = await make_agent(claude, calc).run(question("월 1,200만원으로 바꾸면?"))
    assert resp.message_type == "CLARIFY"
    assert resp.content == text


async def test_clarify_body_quoting_validation_min_display_amount_passes() -> None:
    """(회귀 보호) 422 VALIDATION_FAILED의 minDisplay(1만원)만 인용한 금액도 근거다 (U-2)"""
    text = "월 저축액은 1만원부터 넣을 수 있어요."
    claude = FakeClaude([monthly(12_000_000), text_resp("CLARIFY", text)])
    calc = FakeCalc(error=calc_error("validation-failed-out-of-range-response.json"))
    resp = await make_agent(claude, calc).run(question("월 1,200만원으로 바꾸면?"))
    assert resp.message_type == "CLARIFY"
    assert resp.content == text


async def test_clarify_body_quoting_validation_min_display_period_passes() -> None:
    """(회귀 보호) 422 VALIDATION_FAILED의 minDisplay(0년 1개월)만 인용한 기간도 근거다 (U-2)"""
    text = "기간은 0년 1개월부터 넣을 수 있어요."
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "savings_rate_input_pct": 3.125, "target_period_months": 480}),
        text_resp("CLARIFY", text),
    ])
    calc = FakeCalc(error=calc_error("validation-failed-multi-response.json"))
    resp = await make_agent(claude, calc).run(question("금리 3.125%로 기간 40년이면?"))
    assert resp.message_type == "CLARIFY"
    assert resp.content == text


# 형식마다 같은 근거가 있으면 통과, 근거가 다르면 실패하는 짝 (U-3, U-4)
FORMAT_PAIRS = [
    pytest.param("1.5억이 필요해요.", "약 1.5억", "약 2.5억", id="소수+억"),
    pytest.param("2억이 필요해요.", "목표 2억", "목표 3억", id="억"),
    pytest.param("6달 동안 모아요.", "6달", "7달", id="N달"),
    pytest.param("6개월 동안 모아요.", "6개월", "7개월", id="N개월"),
    pytest.param("월 45 만원씩 넣어요.", "월 45 만원", "월 46 만원", id="띄어쓰기"),
    pytest.param("월 1,234.5만원이에요.", "약 1,234.5만원", "약 1,234.6만원", id="쉼표+소수"),
    pytest.param("5년 동안 모아요.", "기간 5년", "기간 6년", id="N년"),
]


@pytest.mark.parametrize(("content", "same", "other"), FORMAT_PAIRS)
def test_each_format_passes_when_same_token_is_in_evidence(content: str, same: str, other: str) -> None:
    """소수·억·N달·띄어쓰기 금액도 근거에 그대로 있으면 통과한다 (U-4: 같은 규칙으로 대조)"""
    check_numbers(content, [same])


@pytest.mark.parametrize(("content", "same", "other"), FORMAT_PAIRS)
def test_each_format_fails_when_evidence_has_different_value(content: str, same: str, other: str) -> None:
    """같은 형식이라도 근거의 값이 다르면 NumberMismatch (U-3)"""
    with pytest.raises(NumberMismatch):
        check_numbers(content, [other])


@pytest.mark.parametrize(
    ("content", "same", "other"),
    [
        pytest.param("금액은 30,000원이에요.", "30,000원", "40,000원", id="원"),
        pytest.param("금액은 0원이에요.", "0원", "1원", id="0원 경계"),
    ],
)
def test_won_amount_passes_with_same_evidence_and_fails_with_other(content: str, same: str, other: str) -> None:
    """(B1) 원 단위 금액은 근거에 같은 토큰이 있으면 통과, 없으면 NumberMismatch. 원 금액을 늘 거부하는 구현을 막는다"""
    check_numbers(content, [same])
    with pytest.raises(NumberMismatch):
        check_numbers(content, [other])


async def test_validation_error_item_without_display_keys_does_not_break_check() -> None:
    """(회귀 보호) minDisplay·maxDisplay가 없는 오류 항목(TOO_MANY_DECIMALS)이 섞여도 다른 항목의 표시값으로 통과한다"""
    text = "기한은 30년 0개월까지 가능해요. 금리는 소수 둘째 자리까지 넣어 주세요."
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "savings_rate_input_pct": 3.125, "target_period_months": 480}),
        text_resp("CLARIFY", text),
    ])
    calc = FakeCalc(error=calc_error("validation-failed-multi-response.json"))
    resp = await make_agent(claude, calc).run(question("금리 3.125%로 기간 40년이면?"))
    assert resp.message_type == "CLARIFY"
    assert resp.content == text


async def test_amount_stated_in_this_request_text_passes() -> None:
    """(회귀 보호) 이번 요청 text에 글자 그대로 있는 금액(40만원)은 계산 응답에 없어도 근거다 (U-1)"""
    text = "월 40만원이면 목표 약 2,000만원에 가까워져요."
    claude = FakeClaude([monthly(400_000), text_resp("RECALC_RESULT", text)])
    calc = FakeCalc(response={"calcRunId": 77, "display": {"goalAmount": "약 2,000만원"}})
    resp = await make_agent(claude, calc).run(question("월 40만원으로 하면?"))
    assert resp.content == text


async def test_request_text_is_evidence_without_calc_call() -> None:
    """(AC-A) 계산 호출·planContext·history 없이도 이번 요청 text의 금액(40만원)은 근거로 통과하고,
    text에 없는 금액(70만원)으로 바꾸면 NUMBER_MISMATCH. text를 계산 때만 근거로 모으는 구현을 막는다 (U-1)"""
    req_text = "월 40만원이라는 뜻을 설명해 줘"
    ok = "월 40만원은 매달 모으는 금액이에요."
    resp = await make_agent(FakeClaude([text_resp("ANSWER", ok)])).run(question(req_text))
    assert resp.message_type == "ANSWER"
    assert resp.content == ok

    bad = FakeClaude([text_resp("ANSWER", "월 70만원은 매달 모으는 금액이에요.")])
    assert_code(await outcome(make_agent(bad), question(req_text)), "NUMBER_MISMATCH")


async def test_request_text_period_is_evidence_without_calc_call() -> None:
    """계산 호출·planContext·history 없이 이번 요청 text의 단독 기간(6개월)도 근거로 통과하고,
    text에 없는 기간(7개월)으로 바꾸면 NUMBER_MISMATCH. 단독 N개월을 늘 거부하는 구현을 막는다 (U-1, U-4)"""
    req_text = "6개월이라는 뜻을 설명해 줘"
    ok = "6개월 동안 모은다는 뜻이에요."
    resp = await make_agent(FakeClaude([text_resp("ANSWER", ok)])).run(question(req_text))
    assert resp.message_type == "ANSWER"
    assert resp.content == ok

    bad = FakeClaude([text_resp("ANSWER", "7개월 동안 모은다는 뜻이에요.")])
    assert_code(await outcome(make_agent(bad), question(req_text)), "NUMBER_MISMATCH")


# ---- AC-2: 계산 결과에 없는 금액·기간이면 NUMBER_MISMATCH로 실패한다 (C-2) ----

@pytest.mark.parametrize(
    "content",
    [
        "평균 시나리오에서 약 1,800만원이 모여요.",     # 근거에 없는 금액
        "목표까지 580만원 남았어요.",                  # '약 1,580만원'의 꼬리. 토큰 경계가 아니다
        "나쁜 경우 수익은 8만원이에요.",               # '약 18만원'의 꼬리
        "목표는 20,000,000원이에요.",                  # '약 2,000만원'을 원 단위로 환산한 값. 환산하지 않는다
        "36개월 동안 모아요.",                         # '3년 0개월'을 개월로 환산한 값
        "목표 시점은 2029년 2월이에요.",               # 날짜는 'N년 M월' 전체로 대조한다
        "6달 뒤에 다시 보세요.",                       # N달
        "1.5억이 필요해요.",                           # 소수 + 억
        "2억이 필요해요.",                             # 억
        "월 45 만원씩 넣어요.",                        # 숫자와 단위 사이 띄어쓰기도 금액으로 탐지한다
        "월 30 만원씩 넣어요.",                        # '약 30만원'과 띄어쓰기가 달라 그대로 있지 않다
        "약 1,712만원이 모이고 목표까지 300만원 남았어요.",   # 하나는 근거에 있고 하나는 없다
    ],
)
def test_amount_or_period_not_in_evidence_raises_number_mismatch(content: str) -> None:
    """근거 문자열에 토큰 경계로 그대로 없는 금액·기간이 하나라도 있으면 NumberMismatch (U-3, U-4)"""
    with pytest.raises(NumberMismatch):
        check_numbers(content, PLAN_DISPLAY)


# 근거 없는 토큰이 처음·중간에 있고 마지막 토큰은 근거에 있다. 마지막 토큰만 보는 구현을 막는다
UNSUPPORTED_BEFORE_SUPPORTED = [
    pytest.param("목표까지 300만원 남았고 평균 약 1,712만원이 모여요.", id="처음"),
    pytest.param("월 약 30만원씩 넣으면 2,500만원이 되고 평균 약 1,712만원이 모여요.", id="중간"),
    pytest.param("36개월 동안 모으면 목표 시점은 2029년 11월이에요.", id="기간 처음"),
]


@pytest.mark.parametrize("content", UNSUPPORTED_BEFORE_SUPPORTED)
def test_unsupported_token_before_supported_last_token_raises_number_mismatch(content: str) -> None:
    """근거 없는 토큰 뒤에 근거 있는 토큰이 와도 NumberMismatch. 모든 토큰을 대조한다 (U-3)"""
    with pytest.raises(NumberMismatch):
        check_numbers(content, PLAN_DISPLAY)


@pytest.mark.parametrize("content", UNSUPPORTED_BEFORE_SUPPORTED)
async def test_explain_body_with_unsupported_token_before_supported_one_fails(content: str) -> None:
    """EXPLAIN 본문의 처음·중간에 근거 없는 금액·기간이 있으면 마지막 토큰이 근거에 있어도 NUMBER_MISMATCH"""
    claude = FakeClaude([text_resp("EXPLANATION", content)])
    assert_code(await outcome(make_agent(claude), explain()), "NUMBER_MISMATCH")


@pytest.mark.parametrize(
    ("whole", "tail", "evidence"),
    [
        pytest.param("1.5만원이 필요해요.", "5만원이 필요해요.", "약 1.5만원", id="소수점 뒤"),
        pytest.param("1.5억이 필요해요.", "5억이 필요해요.", "약 1.5억", id="소수점 뒤 억"),
        pytest.param("1,234만원이 필요해요.", "234만원이 필요해요.", "약 1,234만원", id="쉼표 뒤"),
        pytest.param("18만원이 필요해요.", "8만원이 필요해요.", "약 18만원", id="숫자 뒤"),
    ],
)
def test_tail_of_other_number_is_not_evidence(whole: str, tail: str, evidence: str) -> None:
    """(B2) 근거 토큰 전체는 통과하지만, 앞 문자가 숫자·쉼표·소수점인 꼬리는 근거가 아니어서 NumberMismatch"""
    check_numbers(whole, [evidence])
    with pytest.raises(NumberMismatch):
        check_numbers(tail, [evidence])


@pytest.mark.parametrize(
    ("whole", "split"),
    [
        pytest.param(["2030년 2월", "2029년 11월"], ["2029년 2월", "2030년 11월"], id="연도·월이 다른 근거 문자열"),
        pytest.param(["2030년 2월부터 2029년 11월"], ["2029년 2월부터 2030년 11월까지"], id="한 문자열의 다른 날짜"),
    ],
)
def test_date_must_appear_whole_in_one_evidence(whole: list[str], split: list[str]) -> None:
    """'N년 M월' 날짜는 한 토큰이다. 날짜 전체가 근거에 그대로 있으면 통과하고, 연도와 월이 서로 다른
    날짜에서만 나오면 NumberMismatch. 연도·월을 따로 찾아 조합하는 구현을 막는다 (U-3, U-4)"""
    content = "목표 시점은 2029년 11월이에요."
    check_numbers(content, whole)
    with pytest.raises(NumberMismatch):
        check_numbers(content, split)


def test_any_amount_with_empty_evidence_raises_number_mismatch() -> None:
    """근거가 비어 있으면 금액 하나만 있어도 NumberMismatch"""
    with pytest.raises(NumberMismatch):
        check_numbers("월 30만원이면 돼요.", [])


async def test_explain_body_with_amount_not_in_plan_display_fails_with_number_mismatch() -> None:
    """EXPLAIN 본문에 planContext에 없는 금액이 있으면 AgentError(NUMBER_MISMATCH)로 응답이 실패한다 (FR-AI-04)"""
    claude = FakeClaude([text_resp("EXPLANATION", "평균 시나리오에서 약 1,800만원이 모여요.")])
    assert_code(await outcome(make_agent(claude), explain()), "NUMBER_MISMATCH")


async def test_recalc_body_with_amount_not_in_calc_display_fails_with_number_mismatch() -> None:
    """RECALC 본문에 계산 응답·planContext·요청 text 어디에도 없는 금액(2,600만원)이면 NUMBER_MISMATCH"""
    claude = FakeClaude([monthly(500_000), text_resp("RECALC_RESULT", "월 50만원이면 평균 약 2,600만원이 모여요.")])
    req = question("월 50만원으로 바꾸면?", plan_context=PLAN_CONTEXT)
    assert_code(await outcome(make_agent(claude), req), "NUMBER_MISMATCH")


async def test_clarify_body_with_amount_not_in_validation_display_fails_with_number_mismatch() -> None:
    """422 뒤 CLARIFY 본문이 minDisplay·maxDisplay에 없는 금액(5,000만원)을 말하면 NUMBER_MISMATCH"""
    claude = FakeClaude([monthly(12_000_000), text_resp("CLARIFY", "월 저축액은 5,000만원까지 넣을 수 있어요.")])
    calc = FakeCalc(error=calc_error("validation-failed-out-of-range-response.json"))
    assert_code(await outcome(make_agent(claude, calc), question("월 1,200만원으로 바꾸면?")), "NUMBER_MISMATCH")


async def test_answer_without_plan_context_with_amount_fails_with_number_mismatch() -> None:
    """planContext도 계산도 없는 QUESTION에서 모델이 금액을 지어내면 NUMBER_MISMATCH"""
    claude = FakeClaude([text_resp("ANSWER", "보통 월 100만원 정도 모으면 돼요.")])
    assert_code(await outcome(make_agent(claude), question("얼마씩 모아야 해요?")), "NUMBER_MISMATCH")


@pytest.mark.parametrize(
    "content",
    [
        "6달 동안 모으면 돼요.",          # N달
        "2년 3개월 동안 모으면 돼요.",    # N년 M개월
        "2031년 3월에 끝나요.",           # 'N년 M월' 날짜
    ],
)
async def test_answer_with_only_unsupported_period_fails_with_number_mismatch(content: str) -> None:
    """금액 없이 근거 없는 기간·날짜만 있는 본문도 ChatAgent.run에서 NUMBER_MISMATCH (U-4)"""
    claude = FakeClaude([text_resp("ANSWER", content)])
    assert_code(await outcome(make_agent(claude), question("기간을 알려줘")), "NUMBER_MISMATCH")


async def test_amount_only_in_history_is_not_evidence() -> None:
    """history에만 있는 금액(70만원)은 근거가 아니다. 근거는 이번 요청 text뿐이다 (U-1)"""
    history = [
        {"role": "user", "content": "월 70만원으로 하면?"},
        {"role": "assistant", "content": "월 70만원이면 목표에 가까워져요."},
    ]
    claude = FakeClaude([text_resp("ANSWER", "네, 월 70만원 기준 그대로예요.")])
    req = question("그럼 기간은 그대로야?", history=history)
    assert_code(await outcome(make_agent(claude), req), "NUMBER_MISMATCH")


async def test_condition_changes_raw_won_value_is_not_converted_to_evidence() -> None:
    """도구 인자 450,000원을 '45만원'으로 환산해 근거로 삼지 않는다. text·display에 없으면 NUMBER_MISMATCH (U-1)"""
    claude = FakeClaude([monthly(450_000), text_resp("RECALC_RESULT", "월 45만원이면 목표에 가까워져요.")])
    calc = FakeCalc(response={"calcRunId": 77, "display": {"goalAmount": "약 2,000만원"}})
    assert_code(await outcome(make_agent(claude, calc), question("월 저축액을 늘리면?")), "NUMBER_MISMATCH")


def saving_chunk(chunk_id: int) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id, content="월 70만원을 모으는 예시", title="저축 이해하기",
        source_url="https://example.com/saving", doc_type=DocType.EDU, org_name="금융감독원", distance=0.1,
    )


async def test_amount_only_in_search_result_is_not_evidence() -> None:
    """(AC-B) search_documents가 돌려준 청크의 금액(70만원)은 근거가 아니다. 같은 검색 뒤 숫자 없는 본문은 통과하고,
    청크의 금액을 말한 본문은 NUMBER_MISMATCH (검색 결과 문자열은 숫자의 근거가 아니다)"""
    search = tool_resp("search_documents", {"query": "저축", "doc_type": "EDU"})

    plain = "저축은 매달 일정한 돈을 모으는 거예요."
    agent = ChatAgent(
        FakeClaude([search, text_resp("ANSWER", plain, [31])]), FakeCalc(), FakeRetriever([saving_chunk(31)]),
        model="claude-opus-5-5", effort="medium", max_rounds=4,
    )
    resp = await agent.run(question("저축을 설명해 줘"))
    assert resp.message_type == "ANSWER"
    assert resp.content == plain
    assert resp.citation_chunk_ids == [31]

    agent = ChatAgent(
        FakeClaude([search, text_resp("ANSWER", "월 70만원을 모으면 돼요.", [31])]), FakeCalc(),
        FakeRetriever([saving_chunk(31)]), model="claude-opus-5-5", effort="medium", max_rounds=4,
    )
    assert_code(await outcome(agent, question("저축을 설명해 줘")), "NUMBER_MISMATCH")


async def test_calc_display_from_previous_request_is_not_evidence_for_next_request() -> None:
    """첫 요청은 계산 display로 통과하고, 같은 에이전트의 다음 요청(계산 없음)에서 같은 금액을 말하면 NUMBER_MISMATCH"""
    claude = FakeClaude([
        monthly(500_000),
        text_resp("RECALC_RESULT", "평균 약 2,501만원이 모여요."),
        text_resp("ANSWER", "아까처럼 약 2,501만원이 모여요."),
    ])
    agent = make_agent(claude)
    first = await agent.run(question("월 50만원으로 바꾸면?"))
    assert first.content == "평균 약 2,501만원이 모여요."
    assert_code(await outcome(agent, question("다시 알려줘")), "NUMBER_MISMATCH")


async def test_validation_display_from_previous_request_is_not_evidence_for_next_request() -> None:
    """(B3) 첫 요청은 422 maxDisplay(1,000만원)로 통과하고, 같은 에이전트의 다음 요청(계산·planContext·history 없음)에서
    같은 금액을 말하면 NUMBER_MISMATCH. 422 표시값은 그 요청의 _Turn에만 담는다 (U-2)"""
    claude = FakeClaude([
        monthly(12_000_000),
        text_resp("CLARIFY", "월 저축액은 1,000만원까지 넣을 수 있어요."),
        text_resp("ANSWER", "1,000만원까지 가능해요."),
    ])
    calc = FakeCalc(error=calc_error("validation-failed-out-of-range-response.json"))
    agent = make_agent(claude, calc)
    first = await agent.run(question("월 1,200만원으로 바꾸면?"))
    assert first.message_type == "CLARIFY"
    assert first.content == "월 저축액은 1,000만원까지 넣을 수 있어요."
    assert_code(await outcome(agent, question("한도를 다시 알려줘")), "NUMBER_MISMATCH")


class RoutedClaude:
    """요청 text별로 응답을 따로 주는 FakeClaude. gated text의 첫 호출은 release가 set될 때까지 보류한다."""

    def __init__(self, routes: dict[str, list[Any]], gated: str):
        self.routes = {k: list(v) for k, v in routes.items()}
        self.gated = gated
        self.entered = asyncio.Event()   # gated 요청이 모델 호출에 들어왔다
        self.release = asyncio.Event()
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        first = kwargs["messages"][0]["content"]
        key = next(k for k in self.routes if f"<request>{k}</request>" in first)
        if key == self.gated and not self.entered.is_set():
            self.entered.set()
            await self.release.wait()
        return self.routes[key].pop(0)


@pytest.mark.parametrize(
    ("calc", "b_text", "b_type", "b_body", "a_body"),
    [
        pytest.param(
            FakeCalc(error=calc_error("validation-failed-out-of-range-response.json")),
            "월 1,200만원으로 바꾸면?", "CLARIFY", "월 저축액은 1,000만원까지 넣을 수 있어요.", "1,000만원까지 가능해요.",
            id="422 표시값",
        ),
        pytest.param(
            FakeCalc(), "월 50만원으로 바꾸면?", "RECALC_RESULT",
            "평균 약 2,501만원이 모여요.", "평균 약 2,501만원이 모여요.",
            id="계산 display",
        ),
    ],
)
async def test_overlapping_request_evidence_is_not_shared(
    calc: FakeCalc, b_text: str, b_type: str, b_body: str, a_body: str
) -> None:
    """같은 ChatAgent에서 A(근거 없음)가 모델 응답을 기다리는 동안 B가 계산·422 표시값으로 통과해도,
    A가 B의 표시값을 말하면 NUMBER_MISMATCH. 근거는 요청마다 따로다 (U-1, U-2)"""
    a_text = "한도를 알려줘"
    claude = RoutedClaude(
        {a_text: [text_resp("ANSWER", a_body)], b_text: [monthly(12_000_000), text_resp(b_type, b_body)]},
        gated=a_text,
    )
    agent = make_agent(claude, calc)
    task_a = asyncio.create_task(outcome(agent, question(a_text)))
    await asyncio.wait_for(claude.entered.wait(), timeout=1)

    b = await agent.run(question(b_text))
    assert b.message_type == b_type
    assert b.content == b_body

    claude.release.set()
    assert_code(await asyncio.wait_for(task_a, timeout=1), "NUMBER_MISMATCH")


# ---- AC-3: 숫자가 없는(대조 대상 토큰이 없는) 설명은 통과한다 (C-3) ----

@pytest.mark.parametrize(
    "content",
    [
        "",
        "적금은 원금이 보장되고 ETF는 시장에 따라 오르내려요.",   # '원금'의 원은 금액이 아니다
        "세금은 15.4%로 가정했어요. 적금 비중은 70%로 바꿔 볼 수 있어요.",   # %는 금액·기간이 아니다
        "결과는 3개 시나리오로 보여드려요.",                      # 단위 없는 숫자
        "월 삼십만원씩 모으면 돼요.",                             # 한글 수사 금액은 보지 않는다(U-4)
    ],
)
def test_body_without_amount_or_period_tokens_passes(content: str) -> None:
    """금액·기간 토큰이 없는 본문은 근거가 비어 있어도 통과한다"""
    check_numbers(content, [])


async def test_general_explanation_without_numbers_passes_through_agent() -> None:
    """(회귀 보호) planContext·계산 없이 숫자 없는 일반 설명은 ANSWER로 그대로 나간다"""
    text = "ETF는 여러 주식을 묶어 거래소에서 사고파는 펀드예요."
    resp = await make_agent(FakeClaude([text_resp("ANSWER", text)])).run(question("ETF가 뭐예요"))
    assert resp.message_type == "ANSWER"
    assert resp.content == text


# ---- AC-4: 검사 순서와 제외 (U-5, U-6) ----

async def test_stock_recommendation_is_rejected_before_number_check() -> None:
    """(회귀 보호) 개별 종목 추천과 근거 없는 금액이 함께 있으면 FORBIDDEN_EXPRESSION이 먼저다 (U-5)"""
    claude = FakeClaude([text_resp("ANSWER", "삼성전자 주식에 월 100만원 넣기를 추천해요.")] * 2)   # 재시도(#98)도 같은 본문
    assert_code(await outcome(make_agent(claude), question("뭐가 좋아요?")), "FORBIDDEN_EXPRESSION")


async def test_number_check_runs_on_body_with_product_name_and_etf_recommendation() -> None:
    """(회귀 보호) 상품명·ETF 추천은 바꾸지 않는다. 금액이 근거에 있으면 통과하고, 없으면 NUMBER_MISMATCH다"""
    body = "KODEX 미국S&P500에 월 약 30만원 중 절반이 들어가요. 이 ETF를 추천해요."
    resp = await make_agent(FakeClaude([text_resp("ANSWER", body)])).run(
        question("ETF에 얼마 들어가요?", plan_context=PLAN_CONTEXT)
    )
    assert resp.content == body
    claude = FakeClaude([text_resp("ANSWER", "KODEX 미국S&P500에 월 777만원이 들어가요.")])
    result = await outcome(make_agent(claude), question("ETF에 얼마 들어가요?", plan_context=PLAN_CONTEXT))
    assert_code(result, "NUMBER_MISMATCH")


async def test_out_of_scope_body_is_not_number_checked() -> None:
    """(회귀 보호) 모델이 OUT_OF_SCOPE로 답하면 본문에 근거 없는 금액이 있어도 실패하지 않는다 (U-6)"""
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", "삼성전자에 100만원 넣으면 1년 뒤 오를 수도 있어요.")])
    resp = await make_agent(claude).run(question("삼성전자 주식 사면 어때요"))
    assert resp.message_type == "OUT_OF_SCOPE"
    assert "100만원" not in resp.content
