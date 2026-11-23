"""세율 표시값 바로 뒤 문장 끝 소수점은 인용이다. 쉼표나 숫자가 붙으면 인용이 아니다(#155 P-5, 잠긴 B1 테스트와 같다).

#233부터 단서를 붙이지 않으므로, 인용 판정은 단서를 지워 이어진 세율을 띄울지(P-6)로 본다."""
import pytest

from tests.test_etf_tax import TAX_FREE_DISPLAY, TAX_NOTE, TAX_NOTE_FREE, answer_of, load

FULL_POINT, FULL_COMMA = "\uff0e", "\uff0c"   # 전각 소수점·쉼표


@pytest.mark.parametrize(
    "answer",
    ["세율은 15.4%.", f"세율은 15.4%{FULL_POINT}", "세율은 15.4%. 그래요"],
    ids=["period", "fullwidth-period", "period-then-space"],
)
async def test_taxable_plan_spaces_a_rate_joined_by_removal_when_a_punctuation_follows_it(answer: str):
    plan = load("plan-context-basic.json")
    assert await answer_of(plan, answer) == answer   # 인용해도 단서를 붙이지 않는다
    content = await answer_of(plan, answer.replace("15.4%", f"15.4{TAX_NOTE}%"))
    assert content == answer.replace("15.4%", "15.4 %")
    assert TAX_NOTE not in content


async def test_tax_free_plan_spaces_a_display_joined_by_removal_when_a_period_follows_it():
    answer = f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}."
    plan = load("plan-context-etf-tax-free.json")
    assert await answer_of(plan, answer) == answer
    content = await answer_of(plan, answer.replace("0%", f"0{TAX_NOTE_FREE}%"))
    assert content == answer.replace("0%", "0 %")
    assert TAX_NOTE_FREE not in content and TAX_FREE_DISPLAY not in content


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
