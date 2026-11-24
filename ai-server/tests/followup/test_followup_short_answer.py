"""Issue #317: 되묻기 뒤 짧은 답에서 답변 실패(TOO_MANY_ROUNDS·NUMBER_MISMATCH·빈 본문)를 없앤다.

e2e S8-8d·8e·8f를 #316 로그로 재현한 원인(2026-11-24):
- NUMBER_MISMATCH: "1억으로 바꿀게"에 되물으며 모델이 "1억 원으로 바꿀 조건이…"라고 썼다. 근거(발화)에는 "1억"만 있고
  숫자 대조가 글자 그대로라 "1억 원"이 걸렸다(message 1686).
- TOO_MANY_ROUNDS: goal_amount=1억 계산이 200으로 끝난 뒤 모델이 빈 인자로 한 번, 같은 인자로 네 번 더 불렀다(message 1708).
  빈 인자에는 "바꿀 조건이 없습니다. 되물으세요"를 돌려줘 이미 받은 결과와 어긋났고, 같은 인자는 매번 Spring을 다시 불렀다.
- 같은 요청을 실제 모델로 재생하자 도구 없이 빈 본문의 RECALC_RESULT를 낸 경우도 있었다(empty_body로 실패).
모델이 무엇을 고를지는 e2e S8 몫이고, 여기서는 재현된 도구 결과·history로 서버가 같은 실패를 내지 않는지 본다.
"""

from typing import Any

import pytest

from app.llm.agent import ChatAgent
from app.llm.errors import AgentError
from app.llm.number_check import NumberMismatch, check_numbers
from app.schemas import ChatRequest, ChatResponse, HistoryItem
from tests.fakes import FakeCalc
from tests.fakes.claude import FakeClaude, FakeRetriever, PassJudge, raw_resp, text_resp, tool_resp

EOK = 100_000_000
NULLS: dict[str, Any] = dict.fromkeys([
    "goal_amount", "monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months",
    "etf_product_code",
])
GOAL_EOK = {**NULLS, "goal_amount": EOK}

# message 1708의 계산 응답에서 답에 쓰는 표시값만 남겼다
CALC_RESULT: dict[str, Any] = {
    "calcRunId": 1159,
    "display": {
        "conditions.current.goalAmount": "약 5,000만원",
        "conditions.changed.goalAmount": "약 10,000만원",
        "changed.AVG.afterTaxAsset": "약 5,456만원",
        "changed.AVG.gapToGoal": "-약 4,544만원 미달",
        "diff.AVG.afterTaxAssetDelta": "변화 없음",
    },
    "changed": {"status": "CALCULATED"},
}
RECALC_TEXT = "목표 금액을 약 5,000만원에서 약 10,000만원으로 바꿨어요. 평균 기준으로 약 4,544만원 모자라요."

# message 1708의 history(S8-8d)
AFTER_MONTHLY_AND_GOAL_LOOKUP = [
    ("user", "매달 150만원으로 하면요?"),
    ("assistant", "월 저축액을 약 150만원으로 바꾸면 평균 기준 세후 자산이 약 6,670만원으로 늘어나요."),
    ("user", "목표금액 얼마야?"),
    ("assistant", "목표 금액은 약 5,000만원이에요."),
]
# S8-8e·8f의 첫 턴 뒤 history
ASKED_ROLE = [
    ("user", "1억으로 바꿀게"),
    ("assistant", "1억 원으로 바꿀 조건이 목표 금액, 월 저축액 중 어느 것인지 알려 주실래요?"),
]


def request(text: str, history: list[tuple[str, str]] | None = None) -> ChatRequest:
    items = [HistoryItem(role=role, content=content) for role, content in history or []]  # type: ignore[arg-type]
    return ChatRequest(
        memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", history=items,
    )


def make_agent(claude: FakeClaude, calc: FakeCalc, max_rounds: int = 6) -> ChatAgent:
    return ChatAgent(
        claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=max_rounds,  # type: ignore[arg-type]
        stock_judge=PassJudge(),
    )


def tool_results(claude: FakeClaude, call: int) -> list[dict[str, Any]]:
    """call번째 모델 요청에 실린 마지막 user 메시지의 도구 결과."""
    content = claude.calls[call]["messages"][-1]["content"]
    assert isinstance(content, list)
    return content


# ---- NUMBER_MISMATCH: '억 원'·'만 원'의 원은 단위를 다시 적은 것이다 ----

@pytest.mark.parametrize(
    ("content", "evidence"),
    [
        pytest.param("1억 원으로 바꿀 조건이 어느 것인가요?", "1억으로 바꿀게", id="억 원"),
        pytest.param("1억원으로 바꿀 조건이 어느 것인가요?", "1억으로 바꿀게", id="억원"),
        pytest.param("1.5억 원이 목표예요.", "목표 1.5억으로", id="소수 억 원"),
        pytest.param("150만 원으로 바꿀까요?", "150만으로 해 줘", id="만 원"),
    ],
)
def test_won_after_eok_or_man_is_the_same_amount_the_user_said(content: str, evidence: str) -> None:
    check_numbers(content, [evidence])


@pytest.mark.parametrize(
    ("content", "evidence"),
    [
        pytest.param("1억 원이 목표예요.", "1억 5천만원으로", id="억 뒤 천만"),
        pytest.param("1억 원이 목표예요.", "1억5000만원으로", id="억 뒤 숫자"),
        pytest.param("1억 원이 목표예요.", "11억으로", id="다른 숫자의 꼬리"),
        pytest.param("2억 원이 목표예요.", "1억으로", id="다른 값"),
        pytest.param("30 만원씩 넣어요.", "약 30만원", id="숫자·단위 사이 띄어쓰기는 그대로 본다"),
    ],
)
def test_won_suffix_does_not_accept_a_different_amount(content: str, evidence: str) -> None:
    with pytest.raises(NumberMismatch):
        check_numbers(content, [evidence])


async def test_clarify_quoting_the_amount_with_won_passes() -> None:
    """S8-8f 첫 턴(message 1686). 되묻기가 "1억 원"이라고 써도 발화의 1억이 근거다"""
    clarify = "1억 원으로 바꿀 조건이 목표 금액, 월 저축액, 적금 비중, 적금 금리, 기한, ETF 종목 중 어느 것인가요?"
    claude = FakeClaude([text_resp("CLARIFY", clarify)])
    resp = await make_agent(claude, FakeCalc()).run(request("1억으로 바꿀게"))
    assert resp.message_type == "CLARIFY"
    assert resp.content == clarify


@pytest.mark.parametrize("text", ["모으는 돈이요", "그거요"])
async def test_answer_quoting_the_pointed_amount_with_won_passes(text: str) -> None:
    """S8-8e·8f 둘째 턴. 가리키는 답 뒤의 답이 앞 발화의 금액을 "1억 원"으로 써도 근거다(#270)"""
    clarify = "1억 원을 목표 금액과 월 저축액 중 어디에 넣을까요?"
    claude = FakeClaude([text_resp("CLARIFY", clarify)])
    resp = await make_agent(claude, FakeCalc()).run(request(text, ASKED_ROLE))
    assert resp.message_type == "CLARIFY"
    assert resp.content == clarify


async def test_pointer_answer_recalculates_with_amount_before_question() -> None:
    """S8-8e. "모으는 돈이요"면 되묻기 앞 발화의 1억으로 계산해 RECALC_RESULT를 낸다"""
    calc = FakeCalc(response=CALC_RESULT)
    claude = FakeClaude([tool_resp("recalculate", GOAL_EOK), text_resp("RECALC_RESULT", "목표 금액을 1억 원으로 바꿨어요.")])
    resp = await make_agent(claude, calc).run(request("모으는 돈이요", ASKED_ROLE))
    assert resp.message_type == "RECALC_RESULT"
    assert calc.calls == [{"goal_amount": EOK}]


# ---- TOO_MANY_ROUNDS: 계산이 끝난 뒤의 같은 계산·빈 계산 ----

async def test_replayed_repeat_calls_do_not_fail_and_spring_is_called_once() -> None:
    """message 1708 재현: 계산 200 뒤 빈 인자 1번, 같은 인자 4번. 같은 계산은 Spring을 다시 부르지 않고,
    마지막 라운드는 도구 없이 답하게 해 계산 결과 답을 낸다"""
    calc = FakeCalc(response=CALC_RESULT)
    claude = FakeClaude([
        tool_resp("recalculate", GOAL_EOK, "tu_1"),
        tool_resp("recalculate", NULLS, "tu_2"),
        tool_resp("recalculate", GOAL_EOK, "tu_3"),
        tool_resp("recalculate", GOAL_EOK, "tu_4"),
        tool_resp("recalculate", GOAL_EOK, "tu_5"),
        text_resp("RECALC_RESULT", RECALC_TEXT),
    ])
    resp = await make_agent(claude, calc).run(request("1억으로 바꿀게", AFTER_MONTHLY_AND_GOAL_LOOKUP))

    assert isinstance(resp, ChatResponse)
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 1159
    assert resp.condition_changes == {"goal_amount": EOK}
    assert calc.calls == [{"goal_amount": EOK}]
    assert claude.calls[-1].get("tool_choice") == {"type": "none"}
    assert all("tool_choice" not in call for call in claude.calls[:-1])


async def test_empty_call_after_a_calc_says_to_answer_with_that_result() -> None:
    """계산 결과가 있는데 빈 인자로 부르면 '되물으세요'가 아니라 받은 결과로 답하라고 돌려준다"""
    calc = FakeCalc(response=CALC_RESULT)
    claude = FakeClaude([
        tool_resp("recalculate", GOAL_EOK, "tu_1"),
        tool_resp("recalculate", NULLS, "tu_2"),
        text_resp("RECALC_RESULT", RECALC_TEXT),
    ])
    await make_agent(claude, calc).run(request("1억으로 바꿀게", AFTER_MONTHLY_AND_GOAL_LOOKUP))

    [result] = tool_results(claude, 2)
    assert result["is_error"] is True
    assert "되물" not in result["content"]
    assert "결과로 답" in result["content"]


async def test_same_call_after_a_calc_is_not_sent_to_spring_again() -> None:
    calc = FakeCalc(response=CALC_RESULT)
    claude = FakeClaude([
        tool_resp("recalculate", GOAL_EOK, "tu_1"),
        tool_resp("recalculate", GOAL_EOK, "tu_2"),
        text_resp("RECALC_RESULT", RECALC_TEXT),
    ])
    resp = await make_agent(claude, calc).run(request("1억으로 바꿀게", AFTER_MONTHLY_AND_GOAL_LOOKUP))

    assert calc.calls == [{"goal_amount": EOK}]
    [result] = tool_results(claude, 2)
    assert result["is_error"] is True and "결과로 답" in result["content"]
    assert resp.calc_run_id == 1159


async def test_different_call_after_a_calc_still_goes_to_spring() -> None:
    """다른 조건으로 다시 계산하는 것은 막지 않는다(모델이 인자를 고친 경우)"""
    calc = FakeCalc(response=CALC_RESULT)
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "goal_amount": 150_000_000}, "tu_1"),
        tool_resp("recalculate", GOAL_EOK, "tu_2"),
        text_resp("RECALC_RESULT", RECALC_TEXT),
    ])
    await make_agent(claude, calc).run(request("1억으로 바꿀게", AFTER_MONTHLY_AND_GOAL_LOOKUP))
    assert calc.calls == [{"goal_amount": 150_000_000}, {"goal_amount": EOK}]


async def test_last_round_without_a_calc_keeps_tools_and_fails_as_before() -> None:
    """계산 결과가 없으면 마지막 라운드도 도구를 막지 않는다. 결과 없는 답을 억지로 만들지 않는다"""
    claude = FakeClaude([tool_resp("search_documents", {"query": "q", "doc_type": "ANY"}, f"tu_{i}") for i in range(2)])
    with pytest.raises(AgentError) as err:
        await make_agent(claude, FakeCalc(), max_rounds=2).run(request("ETF가 뭐야?"))
    assert err.value.code == "TOO_MANY_ROUNDS"
    assert all("tool_choice" not in call for call in claude.calls)


# ---- 빈 본문: 도구 없이 빈 답을 내면 한 번 더 기회를 준다 ----

async def test_empty_model_answer_gets_another_round_with_tools() -> None:
    """재생에서 나온 실패: 도구를 부르지 않고 빈 본문의 RECALC_RESULT를 냈다. 거부하지 않고 다음 라운드에
    도구를 그대로 둔 채 다시 부른다"""
    calc = FakeCalc(response=CALC_RESULT)
    claude = FakeClaude([
        text_resp("RECALC_RESULT", ""),
        tool_resp("recalculate", GOAL_EOK),
        text_resp("RECALC_RESULT", RECALC_TEXT),
    ])
    resp = await make_agent(claude, calc).run(request("1억으로 바꿀게", AFTER_MONTHLY_AND_GOAL_LOOKUP))

    assert resp.message_type == "RECALC_RESULT"
    assert calc.calls == [{"goal_amount": EOK}]
    second = claude.calls[1]
    assert "tool_choice" not in second
    assert second["messages"][-1]["role"] == "user"
    assert isinstance(second["messages"][-1]["content"], str)


async def test_empty_model_answer_in_the_last_round_still_fails() -> None:
    claude = FakeClaude([raw_resp('{"message_type":"ANSWER","content":" ","citation_chunk_ids":[]}')])
    with pytest.raises(AgentError) as err:
        await make_agent(claude, FakeCalc(), max_rounds=1).run(request("1억으로 바꿀게"))
    assert err.value.code == "FORBIDDEN_EXPRESSION"
