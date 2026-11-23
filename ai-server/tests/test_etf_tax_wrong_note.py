"""모델이 쓴 단서는 계획과 맞든 다르든 지우고 어떤 단서도 붙이지 않는다(#233, 결정 3, 정정 11, P-6)."""
import pytest

from tests.test_etf_tax import TAX_NOTE, TAX_NOTE_FREE, answer_of, load

BODY = "세금은 따로 안내해 드릴게요."   # 세율 표시값을 말하지 않는 답


async def test_tax_free_plan_drops_general_note_when_answer_quotes_no_rate():
    assert await answer_of(load("plan-context-etf-tax-free.json"), f"{BODY} {TAX_NOTE}") == BODY


@pytest.mark.parametrize("name", ["plan-context-basic.json", "plan-context-etf0.json", "plan-context-etf-tax-free.json"])
async def test_tax_free_note_is_dropped_and_nothing_is_added_when_answer_quotes_no_rate(name: str):
    assert await answer_of(load(name), f"{BODY} {TAX_NOTE_FREE}") == BODY


@pytest.mark.parametrize("name", ["plan-context-basic.json", "plan-context-etf0.json"])
async def test_taxable_plan_drops_the_model_general_note_when_answer_quotes_no_rate(name: str):
    assert await answer_of(load(name), f"{BODY} {TAX_NOTE}") == BODY   # 비과세가 아닌 계획의 tax.note도 지운다


@pytest.mark.parametrize(
    ("name", "rate", "right"),
    [
        ("plan-context-etf-tax-free.json", "assumptions.etfGainTaxRatePct", TAX_NOTE_FREE),
        ("plan-context-basic.json", "assumptions.taxRatePct", TAX_NOTE),
        ("plan-context-etf0.json", "assumptions.taxRatePct", TAX_NOTE),
    ],
)
async def test_right_note_written_twice_by_model_is_removed_entirely(name: str, rate: str, right: str):
    plan = load(name)
    body = f"적용한 세율은 {plan['display'][rate]}로 가정했어요."
    content = await answer_of(plan, f"{body}\n\n{right}\n\n{right}")
    assert content == body
    assert TAX_NOTE not in content and TAX_NOTE_FREE not in content
