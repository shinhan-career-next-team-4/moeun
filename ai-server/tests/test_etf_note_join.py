"""세금 단서를 지울 때 양옆 글자가 이어져 세율 인용이나 종목명이 새로 생기지 않는다(#155 라운드 3, B1)."""
import pytest

from app.llm.agent import AgentError
from tests.test_etf_name_correction import etf0_request, rejected
from tests.test_etf_tax import TAX_NOTE, TAX_NOTE_FREE, answer_of, load, text_resp


async def test_note_between_halves_of_the_tax_free_display_is_not_a_quote():
    answer = f"ETF 매매차익 세율은 0%{TAX_NOTE}(국내 주식형 ETF 매매차익 비과세)예요."
    content = await answer_of(load("plan-context-etf-tax-free.json"), answer)
    assert TAX_NOTE_FREE not in content
    assert TAX_NOTE not in content
    assert "0%(국내 주식형 ETF 매매차익 비과세)" not in content


async def test_note_inside_the_tax_free_display_across_a_space_is_not_a_quote():
    answer = f"ETF 매매차익 세율은 0%(국내 주식형 ETF 매매차익 {TAX_NOTE}비과세)예요."
    content = await answer_of(load("plan-context-etf-tax-free.json"), answer)
    assert TAX_NOTE_FREE not in content
    assert "0%(국내 주식형 ETF 매매차익 비과세)" not in content


async def test_dropping_a_note_that_joins_an_allowed_etf_name_is_rejected_once_on_an_etf0_plan():
    error, claude = await rejected(
        etf0_request(),
        text_resp(f"이 계획에는 코스피200 추종 {TAX_NOTE_FREE}ETF를 썼어요."),
        text_resp("호출되면 안 돼요"),
    )
    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


async def test_note_between_digits_does_not_make_a_new_number():
    plan = load("plan-context-basic.json")
    with pytest.raises(AgentError) as caught:
        await answer_of(plan, f"목표까지 3{TAX_NOTE}00만원이 필요해요.")
    assert caught.value.code == "NUMBER_MISMATCH"
