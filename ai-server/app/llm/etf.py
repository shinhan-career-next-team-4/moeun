import re

from app.llm.plan import plan_etf_name
from app.rag.etf_codes import doc_codes_for, product_from_display_name
from app.schemas import ChatRequest, DocType


def plan_product(req: ChatRequest) -> str | None:
    """계획의 종목 코드. Spring이 준 etfProductCode를 우선하고(모르는 코드는 doc_codes_for가 버려 필터가 없다),
    없을 때(구버전 요청·ETF 0%)만 표시 이름의 키워드로 읽는다(#186)."""
    if req.plan_context is None:
        return None
    code = req.plan_context.result.assumptions.etf_product_code
    return code if code is not None else product_from_display_name(plan_etf_name(req))


def stored_plan_product(req: ChatRequest) -> str | None:
    """계획에 저장된 종목 코드. 대화로 종목을 바꿀 때 계획과 같은 종목인지 보는 데만 쓴다(D-097).

    Spring이 준 conditions.etfProductCode(ETF 0%여도 있음, #297)를 우선한다. 없으면(#297 전 Spring) plan_product다.
    검색 필터(search_etf_codes)는 계산에 쓴 종목(plan_product)을 써서 ETF 0% 계획에 필터를 걸지 않는다.
    """
    if req.plan_context is None:
        return None
    conditions = req.plan_context.conditions
    if conditions is not None and conditions.etf_product_code is not None:
        return conditions.etf_product_code
    return plan_product(req)


PLAN_PRODUCT = "PLAN"   # 모델이 "내 계획의 ETF"를 가리킬 때 쓰는 표시. 코드는 서버가 계획에서 읽는다


# 발화가 내 계획의 ETF를 가리키는 말. "내가 모르는 ETF"처럼 앞 글자가 한글로 이어지는 말은 가리키지 않는다
_MY_ETF = re.compile(r"(?<![가-힣])(?:내|이|지금|우리)\s*(?:계획의\s*)?(?:ETF|이티에프)", re.IGNORECASE)


def search_etf_codes(req: ChatRequest, doc_type: DocType | None, asked: object) -> list[str]:
    """검색을 좁힐 문서 etf_code(#183). 공시 검색에서 모델이 종목을 가리킨 경우에만 걸고, 아니면 빈 목록(필터 없음)이다.
    교육자료는 etf_code 없이 적재돼 필터를 걸면 근거가 0건이 되므로 공시(DISCLOSURE)에만 건다. 모델이 쓴 코드는
    enum에 있는 것만 받고, 질문이 계획의 종목을 가리키면(PLAN) 코드는 계획의 etfProductCode(없으면 표시 이름)에서
    서버가 읽는다. 모델이 종목을 정하지 않았어도 사용자 발화가 "내 ETF"처럼 계획의 ETF를 가리키면 계획의 종목으로 건다
    (실제 LLM 점검에서 모델이 PLAN을 빼먹어 다른 종목 문서를 인용한 적이 있다, #189)."""
    if doc_type != DocType.DISCLOSURE:
        return []
    planned = plan_product(req)
    asked_products = [p for p in asked if isinstance(p, str)] if isinstance(asked, list) else []
    products = [planned if p == PLAN_PRODUCT else p for p in asked_products]
    codes = doc_codes_for([p for p in products if p])
    if not codes and planned and req.text and _MY_ETF.search(req.text):
        codes = doc_codes_for([planned])
    return codes
