"""세율 표시값 바로 뒤 문장 끝 소수점은 인용이다. 쉼표나 숫자가 붙으면 인용이 아니다(#155 P-5, 잠긴 B1 테스트와 같다)."""
import pytest

from tests.test_etf_tax import TAX_FREE_DISPLAY, TAX_NOTE, TAX_NOTE_FREE, answer_of, load

FULL_POINT, FULL_COMMA = "\uff0e", "\uff0c"   # 전각 소수점·쉼표


@pytest.mark.parametrize(
    "answer",
    ["세율은 15.4%.", f"세율은 15.4%{FULL_POINT}", "세율은 15.4%. 그래요"],
    ids=["period", "fullwidth-period", "period-then-space"],
)
async def test_taxable_plan_gets_the_note_when_a_punctuation_follows_the_rate(answer: str):
    content = await answer_of(load("plan-context-basic.json"), answer)
    assert content == f"{answer}\n\n{TAX_NOTE}"


async def test_tax_free_plan_gets_only_the_tax_free_note_when_a_period_follows_the_rate():
    answer = f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}."
    content = await answer_of(load("plan-context-etf-tax-free.json"), answer)
    assert content == f"{answer}\n\n{TAX_NOTE_FREE}"


@pytest.mark.parametrize(
    "answer",
    [
        "세율은 15.4%.5예요.",
        "세율은 15.4%,5예요.",
        "세율은 15.4%5예요.",
        "세율은 15.4%, 그래요",
        f"세율은 15.4%{FULL_COMMA}그래요",
    ],
)
async def test_digit_after_the_rate_or_its_punctuation_is_not_a_quote(answer: str):
    assert await answer_of(load("plan-context-basic.json"), answer) == answer
