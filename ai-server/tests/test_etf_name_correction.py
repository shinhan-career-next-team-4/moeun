"""계획 종목 표기 검사와 교정 지시는 계획 종목의 표시값을 기준으로 한다(#155, CP-06).

#168(CF-07): ETF 상품명(브랜드)은 허용이라 치환·거부하지 않는다. 거부는 계획 종목을 다른 ETF로 바꿔 말한 경우뿐이다."""
from typing import Any

import pytest

from app.llm import agent, assemble
from app.llm.agent import AgentError
from tests.test_etf_tax import ETF_NAME_KEY, load, make_request, run, text_resp
from tests.test_plan_context import FakeClaude, make_agent

KOSPI = "코스피200 추종 ETF"
GENERIC = "S&P500 추종 ETF"
NASDAQ = "나스닥 추종 ETF"


def retry_text(call: dict[str, Any]) -> str:
    return str(call["messages"][-1]["content"])


async def test_kospi_plan_retry_instruction_names_the_plan_etf_and_keeps_generic_for_others():
    plan = load("plan-context-etf-tax-free.json")
    assert plan["display"][ETF_NAME_KEY] == KOSPI
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])

    resp, claude = await run(req, text_resp(f"이 계획에는 {NASDAQ}를 썼어요."), text_resp(f"이 계획에는 {KOSPI}를 썼어요."))

    assert len(claude.calls) == 2
    instruction = retry_text(claude.calls[1])
    assert f"계획의 종목은 '{KOSPI}'" in instruction and f"'{GENERIC}'" in instruction
    assert resp.content.startswith(f"이 계획에는 {KOSPI}를 썼어요.")


@pytest.mark.parametrize("plan_file", ["plan-context-etf-tax-free.json", "plan-context-basic.json"])
async def test_brand_product_name_passes_unchanged_in_a_plan_answer(plan_file: str):
    """ETF 상품명은 허용이라 계획 설명에서도 바꾸지 않는다. 계획 종목과 다른 허용 ETF 표현이 없으면 거부도 없다(#168)."""
    plan = load(plan_file)
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])
    answer = "이 계획에는 TIGER 미국S&P500 ETF를 썼어요."

    resp, claude = await run(req, text_resp(answer))

    assert len(claude.calls) == 1
    assert resp.content.startswith(answer)


def etf0_request() -> Any:
    plan = load("plan-context-etf0.json")
    assert ETF_NAME_KEY not in plan["display"]
    return make_request(plan, "EXPLAIN", plan["calcRunId"])


async def rejected(req: Any, *responses: Any) -> tuple[AgentError, Any]:
    claude = FakeClaude(list(responses))
    with pytest.raises(AgentError) as caught:
        await make_agent(claude).run(req)
    return caught.value, claude


async def test_etf0_plan_answer_naming_a_brand_product_passes_in_one_call():
    """ETF 상품명(브랜드)은 허용이라 ETF 0% 계획에서도 거부하지 않는다. 거부는 허용 ETF 표현 언급뿐이다(#168, CF-07)."""
    answer = "KODEX 200이에요. 적금으로 모아요."
    resp, claude = await run(etf0_request(), text_resp(answer))

    assert len(claude.calls) == 1 and resp.content.startswith(answer)


@pytest.mark.parametrize("etf_name", [KOSPI, GENERIC, NASDAQ])
async def test_etf0_plan_is_rejected_once_without_retry_when_the_answer_names_an_allowed_etf(etf_name: str):
    error, claude = await rejected(
        etf0_request(), text_resp(f"{etf_name}에 넣어요. 적금으로 모아요."), text_resp("호출되면 안 돼요")
    )

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


async def test_etf0_plan_answer_without_any_etf_passes_in_one_call():
    resp, claude = await run(etf0_request(), text_resp("적금으로 모아요. 기간은 3년이에요."))

    assert len(claude.calls) == 1 and resp.content.startswith("적금으로 모아요. 기간은 3년이에요.")


async def test_etf0_plan_never_offers_an_etf_in_any_retry_instruction():
    """어떤 교정 지시에도 이 계획의 종목으로 ETF를 제시하지 않는다(AC-8)."""
    _, claude = await run(
        etf0_request(), text_resp("삼성전자 주식을 추천해요. 적금으로 모아요."), text_resp("적금으로 모아요.")
    )

    assert len(claude.calls) == 2   # 개별 종목 추천 재시도는 기존대로 간다
    instruction = retry_text(claude.calls[1])
    assert "계획의 종목은" not in instruction
    assert not [name for name in (KOSPI, GENERIC, NASDAQ) if name in instruction]


async def test_allowed_etf_expressions_in_a_kospi_plan_are_not_rejected_as_product_names():
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])
    answer = f"이 계획의 {KOSPI}와 {GENERIC}, {NASDAQ}는 서로 달라요."

    resp, claude = await run(req, text_resp(answer))

    assert len(claude.calls) == 1
    assert resp.content.startswith(answer)


def test_allowed_etf_names_are_one_constant_with_the_three_expressions():
    assert tuple(assemble.ALLOWED_ETF_NAMES) == (KOSPI, GENERIC, NASDAQ)


def add_allowed_etf(monkeypatch: pytest.MonkeyPatch) -> str:
    """상수 하나에 항목을 더한다. agent가 이름을 따로 가져와도 같은 값을 보게 한다."""
    new_name = "코스닥150 추종 ETF"
    patched = (*assemble.ALLOWED_ETF_NAMES, new_name)
    monkeypatch.setattr(assemble, "ALLOWED_ETF_NAMES", patched)
    monkeypatch.setattr(agent, "ALLOWED_ETF_NAMES", patched, raising=False)
    return new_name


async def test_adding_an_etf_to_the_constant_reaches_the_replacement_examples(monkeypatch: pytest.MonkeyPatch):
    new_name = add_allowed_etf(monkeypatch)
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])

    _, claude = await run(req, text_resp(f"이 계획에는 {NASDAQ}예요."), text_resp(f"이 계획에는 {KOSPI}예요."))

    instruction = retry_text(claude.calls[1])
    assert f"계획의 종목은 '{KOSPI}'" in instruction
    assert all(name in instruction for name in (GENERIC, NASDAQ, new_name))


async def test_adding_an_etf_to_the_constant_reaches_the_name_check(monkeypatch: pytest.MonkeyPatch):
    new_name = add_allowed_etf(monkeypatch)

    error, claude = await rejected(
        etf0_request(), text_resp(f"{new_name}에 넣어요. 적금으로 모아요."), text_resp("호출되면 안 돼요")
    )

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


async def test_request_without_plan_context_keeps_brand_product_names():
    """planContext가 없는 일반 ETF 질문도 상품명을 바꾸거나 거부하지 않는다(#168, CF-07)."""
    req = make_request(None, "QUESTION", text="ETF가 뭐예요?")

    resp, claude = await run(req, text_resp("TIGER 미국S&P500 ETF예요."))

    assert len(claude.calls) == 1
    assert resp.content == "TIGER 미국S&P500 ETF예요."
