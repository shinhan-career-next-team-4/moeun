"""세율 인용 판정: 표시값 전체가 독립된 숫자(앞이 숫자·소수점이 아님)여야 인용이다(결정 5, 정정 11, P-5·P-6).

#233부터 단서를 붙이지 않으므로 판정은 단서를 지운 자리에 한 칸을 띄울지(P-6)에만 쓴다.
`|` 자리에 단서를 넣어 세율 표시값을 둘로 나누고, 지운 뒤 이어진 글자가 인용이면 띄우고 아니면 붙인다."""
import pytest

from tests.test_etf_tax import TAX_FREE_DISPLAY, TAX_NOTE, TAX_NOTE_FREE, answer_of, load

FULL_ZERO, FULL_ONE, FULL_THREE = "\uff10", "\uff11", "\uff13"   # 전각 숫자 0·1·3
FULL_POINT, FULL_COMMA = "\uff0e", "\uff0c"   # 전각 소수점·쉼표
PARTIAL = "10%(국내 주식형 ETF 매매차익 비과세)"   # 비과세 표시값의 꼬리와 같지만 앞이 숫자다


def split(answer: str, note: str) -> str:
    return answer.replace("|", note)


def gapped(answer: str) -> str:
    return answer.replace("|", " ")


def joined(answer: str) -> str:
    return answer.replace("|", "")


@pytest.mark.parametrize("note", [TAX_NOTE_FREE, TAX_NOTE], ids=["tax-free-note", "general-note"])
async def test_real_tax_free_display_quote_gets_no_note_and_a_split_one_is_spaced(note: str):
    answer = f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}이에요."
    plan = load("plan-context-etf-tax-free.json")
    assert await answer_of(plan, answer) == answer   # 인용해도 단서를 붙이지 않는다(#233)
    split_answer = "ETF 매매차익 세율은 0|%(국내 주식형 ETF 매매차익 비과세)이에요."
    content = await answer_of(plan, split(split_answer, note))
    assert content == gapped(split_answer)
    assert TAX_FREE_DISPLAY not in content


async def test_partial_match_of_tax_free_display_gets_no_note():
    answer = f"ETF 매매차익 세율은 {PARTIAL}이에요."
    assert await answer_of(load("plan-context-etf-tax-free.json"), answer) == answer


@pytest.mark.parametrize("note", [TAX_NOTE_FREE, TAX_NOTE], ids=["tax-free-note", "general-note"])
async def test_partial_match_with_model_written_note_has_no_note_left(note: str):
    answer = f"ETF 매매차익 세율은 {PARTIAL}이에요."
    content = await answer_of(load("plan-context-etf-tax-free.json"), f"{answer} {note}")
    assert content == answer


async def test_partial_quote_does_not_count_so_a_rate_joined_by_removal_is_spaced():
    """부분 일치는 이미 있는 인용이 아니므로, 단서를 지워 새로 생기는 독립된 세율은 띄운다(P-6)."""
    answer = f"ETF 차익은 {PARTIAL}가 아니라 적금 이자 세율은 15.4|%예요."
    content = await answer_of(load("plan-context-etf-tax-free.json"), split(answer, TAX_NOTE_FREE))
    assert content == gapped(answer)
    assert TAX_NOTE_FREE not in content and TAX_NOTE not in content


@pytest.mark.parametrize(
    ("answer", "quoted"),
    [
        ("세율은 15.4|%예요.", True),
        ("15.4|%로 가정했어요.", True),
        ("세율은 약15.4|%예요.", True),
        ("세율은 115.4|%예요.", False),
        ("세율은 3.15.4|%예요.", False),
        ("세율은 115.4%가 아니라 15.4|%예요.", True),
    ],
)
@pytest.mark.parametrize("name", ["plan-context-basic.json", "plan-context-etf0.json"])
async def test_savings_rate_quote_needs_an_independent_number(name: str, answer: str, quoted: bool):
    """단서를 지워 이어진 세율이 독립된 숫자일 때만 띄운다. 어느 쪽이든 단서는 남지 않는다."""
    content = await answer_of(load(name), split(answer, TAX_NOTE))
    assert content == (gapped(answer) if quoted else joined(answer))
    assert TAX_NOTE not in content and TAX_NOTE_FREE not in content


@pytest.mark.parametrize(
    "display",
    [f"{head}0%(국내 주식형 ETF 매매차익 비과세)" for head in (FULL_ONE, f"{FULL_ZERO}.", f"{FULL_THREE}{FULL_COMMA}")],
    ids=["fullwidth-digit", "fullwidth-digit-and-point", "fullwidth-comma"],
)
@pytest.mark.parametrize("note", ["", TAX_NOTE_FREE], ids=["no-model-note", "model-note"])
async def test_fullwidth_digit_or_point_before_the_tax_free_display_is_not_a_quote(display: str, note: str):
    """앞 글자가 전각 숫자·소수점·쉼표면 독립된 숫자가 아니다. 모델이 단서를 썼어도 지운다(결정 15)."""
    answer = f"ETF 매매차익 세율은 {display}이에요."
    content = await answer_of(load("plan-context-etf-tax-free.json"), f"{answer} {note}".strip())
    assert content == answer


@pytest.mark.parametrize(
    "answer",
    [
        f"세율은 {FULL_ONE}15.4%예요.",
        f"세율은 3{FULL_POINT}15.4%예요.",
        f"세율은 {FULL_THREE}{FULL_COMMA}15.4%예요.",
        "세율은 3,15.4%예요.",
    ],
    ids=["fullwidth-digit", "fullwidth-point", "fullwidth-comma", "ascii-comma"],
)
async def test_savings_rate_with_a_fullwidth_digit_point_or_comma_before_it_is_not_a_quote(answer: str):
    assert await answer_of(load("plan-context-basic.json"), answer) == answer
