"""ETF 0% 계획은 범위 밖 답이라도 허용 ETF 표현으로 종목을 언급하면 재시도 없이 거부한다(#155 AC-8, P-4).
상품명(브랜드)은 허용이라 이 검사에서 보지 않는다(#168)."""
import pytest

from tests.test_etf_name_correction import GENERIC, NASDAQ, etf0_request, rejected
from tests.test_etf_tax import run, text_resp


@pytest.mark.parametrize("name", [NASDAQ, GENERIC])
async def test_etf0_plan_out_of_scope_answer_naming_an_etf_is_rejected_once(name: str):
    error, claude = await rejected(
        etf0_request(),
        text_resp(f"이 계획에는 {name}을 썼어요.", "OUT_OF_SCOPE"),
        text_resp("호출되면 안 돼요"),
    )

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


async def test_etf0_plan_out_of_scope_answer_without_an_etf_keeps_the_fixed_copy():
    resp, claude = await run(etf0_request(), text_resp("다른 주제예요.", "OUT_OF_SCOPE"))

    assert resp.message_type == "OUT_OF_SCOPE"
    assert len(claude.calls) == 1
