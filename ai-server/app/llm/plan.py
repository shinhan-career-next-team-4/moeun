from app.core.contract import ETF_NAME_KEY as ETF_NAME_KEY
from app.core.contract import ETF_TAX_RATE_KEY as ETF_TAX_RATE_KEY
from app.core.contract import TAX_RATE_KEY as TAX_RATE_KEY
from app.schemas import ChatRequest


def plan_etf_name(req: ChatRequest) -> str | None:
    """계획이 쓴 종목의 표시값. 없으면 None이고 일반 표현은 'S&P500 추종 ETF'다(#155, CP-06)."""
    return (req.plan_context.display.get(ETF_NAME_KEY) or None) if req.plan_context else None


def plan_without_etf(req: ChatRequest) -> bool:
    """planContext는 있지만 종목 표시값이 없다(ETF 0%). planContext가 없는 일반 질문과 구분한다(#155, P-3·P-4)."""
    return req.plan_context is not None and plan_etf_name(req) is None
