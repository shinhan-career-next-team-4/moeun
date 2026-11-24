"""백엔드 ETF 종목 코드와 RAG 문서의 etf_code를 잇는다(#183).

백엔드 종목 코드(SP500, 069500, 133690)와 rag_document.etf_code는 다르다. S&P500은 상품이 둘(KODEX 379800,
TIGER 360750)이라 문서 코드 둘로 펼친다.
"""

PRODUCT_DOC_CODES: dict[str, list[str]] = {
    "SP500": ["379800", "360750"],
    "069500": ["069500"],
    "133690": ["133690"],
}

# 계획 display의 assumptions.etfProductName은 상품명 대신 이 표현을 쓴다(CP-06)
_DISPLAY_KEYWORDS = {"S&P500": "SP500", "코스피200": "069500", "나스닥100": "133690"}
# 대화로 바꿀 수 있는 종목 코드와 그 지수 이름(D-097). ai-server의 종목 목록은 이 파일 한 곳이고, 도구 enum과 설명이
# 여기서 나온다. 활성 여부는 Spring이 검증한다(없는 코드는 422). 종목을 더하면 이 파일과 프롬프트를 같이 고친다
PRODUCT_INDEX_NAMES: dict[str, str] = {product: keyword for keyword, product in _DISPLAY_KEYWORDS.items()}


def doc_codes_for(products: list[str]) -> list[str]:
    """종목 코드를 문서 etf_code 목록으로 바꾼다. 모르는 코드는 버리고 중복은 한 번만 둔다."""
    codes: list[str] = []
    for product in products:
        for code in PRODUCT_DOC_CODES.get(product, []):
            if code not in codes:
                codes.append(code)
    return codes


def product_from_display_name(name: str | None) -> str | None:
    if not name:
        return None
    return next((product for keyword, product in _DISPLAY_KEYWORDS.items() if keyword in name), None)
