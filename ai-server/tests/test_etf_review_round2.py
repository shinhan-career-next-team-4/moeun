"""2라운드 리뷰 재현(B1~B6)을 고정한다(#155). 잠긴 테스트는 건드리지 않고 새 파일에 둔다."""
import pytest

from app.llm import agent, assemble
from app.llm.assemble import ForbiddenExpression, check_plan_etf_claims
from tests.test_etf_name_correction import GENERIC, KOSPI, NASDAQ, etf0_request, rejected, retry_text
from tests.test_etf_tax import TAX_FREE_DISPLAY, TAX_NOTE, TAX_NOTE_FREE, answer_of, load, make_request, run, text_resp


# B1: 세율 표시값 바로 뒤의 소수점·쉼표도 앞과 같은 경계로 본다(P-5)
@pytest.mark.parametrize("tail", ["\uff0c\uff11", ",1", "\uff0e5", ".5", "\uff0c", ","])
async def test_b1_separator_after_the_tax_free_display_is_not_a_quote(tail: str):
    content = await answer_of(
        load("plan-context-etf-tax-free.json"), f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}{tail}로 적었어요."
    )
    assert TAX_NOTE_FREE not in content


async def test_b1_separator_after_the_savings_rate_is_not_a_quote():
    plan = load("plan-context-basic.json")
    rate = plan["display"]["assumptions.taxRatePct"]
    assert TAX_NOTE not in await answer_of(plan, f"적금 이자 세율은 {rate}\uff0c\uff11이에요.")
    assert TAX_NOTE in await answer_of(plan, f"적금 이자 세율은 {rate}예요.")


# B2: 단서 문장 사이 공백이 줄바꿈이어도 같은 단서로 본다
async def test_b2_note_with_newlines_is_not_duplicated():
    plan = load("plan-context-etf-tax-free.json")
    rate = plan["display"]["assumptions.taxRatePct"]
    newline_note = TAX_NOTE_FREE.replace(" ", "\n")
    content = await answer_of(plan, f"적금 이자 세율은 {rate}예요.\n\n{newline_note}")
    assert content.count(TAX_NOTE_FREE) == 1
    assert newline_note not in content and "\n" not in content.split("예요.", 1)[1].strip()


async def test_b2_wrong_note_with_newlines_is_replaced():
    plan = load("plan-context-etf-tax-free.json")
    rate = plan["display"]["assumptions.taxRatePct"]
    content = await answer_of(plan, f"적금 이자 세율은 {rate}예요.\n\n{TAX_NOTE.replace(' ', chr(10))}")
    assert content.count(TAX_NOTE_FREE) == 1 and TAX_NOTE.split()[0] not in content.replace(TAX_NOTE_FREE, "")


# B3: 계획 종목을 다른 허용 ETF로 바꿔 말하면 거부, 다른 ETF를 따로 설명하는 문장은 허용
async def test_b3_plan_etf_relabeled_with_another_allowed_name_is_retried():
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])

    resp, claude = await run(req, text_resp(f"이 계획에는 {GENERIC}를 썼어요."), text_resp(f"이 계획에는 {KOSPI}를 썼어요."))

    assert len(claude.calls) == 2
    assert f"계획의 종목은 '{KOSPI}'" in retry_text(claude.calls[1])
    assert GENERIC not in resp.content and KOSPI in resp.content


async def test_b3_separate_explanation_of_another_etf_is_allowed():
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])
    answer = f"이 계획에는 {KOSPI}를 썼어요. {GENERIC}는 미국 대형주 500개를 따라가요."

    resp, claude = await run(req, text_resp(answer))

    assert len(claude.calls) == 1 and resp.content.startswith(answer)


async def test_b3_foreign_stock_sentence_without_plan_reference_passes_in_one_call():
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])
    answer = f"{GENERIC} 같은 해외 종목은 과세예요. 이 계획에는 {KOSPI}를 썼어요."

    resp, claude = await run(req, text_resp(answer))

    assert len(claude.calls) == 1 and resp.content.startswith(answer)


def test_b3_check_plan_etf_claims_rules():
    check_plan_etf_claims(f"{GENERIC} 같은 해외 종목은 과세예요.", KOSPI)
    check_plan_etf_claims(f"{GENERIC}는 미국 지수를 따라가요.", KOSPI)
    check_plan_etf_claims(f"이 계획의 {KOSPI}와 {NASDAQ}는 달라요.", KOSPI)
    check_plan_etf_claims(f"이 계획에는 {GENERIC}를 썼어요.", None)   # 표시값이 없으면 검사하지 않는다
    with pytest.raises(ForbiddenExpression):
        check_plan_etf_claims(f"이 종목은 {NASDAQ}예요.", KOSPI)


# B6: 상수에 더한 표현은 표시값 있는 계획의 상품명 검사에서도 인식한다
async def test_b6_added_allowed_name_is_not_a_product_name_in_a_kospi_plan(monkeypatch: pytest.MonkeyPatch):
    new_name = "ACE 미국 지수 추종 ETF"
    patched = (*assemble.ALLOWED_ETF_NAMES, new_name)
    monkeypatch.setattr(assemble, "ALLOWED_ETF_NAMES", patched)
    monkeypatch.setattr(agent, "ALLOWED_ETF_NAMES", patched, raising=False)
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])
    answer = f"이 계획의 {KOSPI}와 달리 {new_name}는 미국 지수를 따라가요."

    resp, claude = await run(req, text_resp(answer))

    assert len(claude.calls) == 1 and resp.content.startswith(answer)


async def test_b6_unregistered_etf_name_passes_unchanged_in_a_kospi_plan():
    """등록하지 않은 ETF 이름은 상품명(브랜드)과 같이 허용이라 거부·재시도하지 않는다(#168, CF-07)."""
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])

    resp, claude = await run(req, text_resp("ACE 미국 지수 추종 ETF예요."))

    assert len(claude.calls) == 1 and resp.content.startswith("ACE 미국 지수 추종 ETF예요.")


async def test_b5_model_written_general_note_alone_is_not_rejected_when_nothing_was_removed():
    """지운 문장이 없으면 P-6대로 비과세가 아닌 계획의 tax.note는 지금처럼 둔다."""
    resp, claude = await run(etf0_request(), text_resp(TAX_NOTE))

    assert len(claude.calls) == 1 and resp.content == TAX_NOTE


# 보정 뒤 본문이 비면 정상 응답을 내지 않고 재시도 없이 기존 거부로 끝낸다(결정 18, P-10)
@pytest.mark.parametrize("note", [TAX_NOTE_FREE, TAX_NOTE_FREE.replace(" ", "\n")], ids=["free", "newline"])
async def test_empty_body_after_dropping_the_note_is_rejected_without_retry_on_an_etf0_plan(note: str):
    error, claude = await rejected(etf0_request(), text_resp(note), text_resp("호출되면 안 돼요"))

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


@pytest.mark.parametrize("note", [TAX_NOTE, TAX_NOTE.replace(" ", "\n")], ids=["general", "newline"])
async def test_empty_body_after_dropping_the_general_note_is_rejected_on_a_tax_free_plan(note: str):
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "EXPLAIN", plan["calcRunId"])

    error, claude = await rejected(req, text_resp(note), text_resp("호출되면 안 돼요"))

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


async def test_whitespace_only_answer_is_rejected_without_retry():
    error, claude = await rejected(etf0_request(), text_resp("   \n "), text_resp("호출되면 안 돼요"))

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1
