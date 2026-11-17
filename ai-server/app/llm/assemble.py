import re

GENERIC_ETF_NAME = "S&P500 추종 ETF"

# 본문에 상품명을 쓰지 않는다(D-028, NFR-USA-02). 출처 제목은 원래 이름 그대로 두므로 모델 본문에만 건다.
# 약칭·다른 브랜드까지 다 잡지 않는다. 오탐이 큰 짧은 브랜드(ACE, SOL 등)는 넣지 않았다
_PRODUCT_NAMES = re.compile("KODEX|TIGER|KBSTAR|ARIRANG|HANARO", re.IGNORECASE)

# 긍정 추천 활용형만 본다. "추천하지 않아요"·"추천드리지 않아요"는 활용형이 달라 걸리지 않는다(CF-07)
_POSITIVE_RECOMMENDATION = re.compile("추천(?:해요|합니다|드려요|드립니다)")


class ForbiddenExpression(Exception):
    """본문에 나가면 안 되는 표현이 있다. 호출자가 AgentError로 바꿔 올린다."""


def replace_product_names(content: str) -> str:
    """상품명을 'S&P500 추종 ETF'로 바꾼다. 없으면 글자 그대로 돌려준다."""
    return _PRODUCT_NAMES.sub(GENERIC_ETF_NAME, content)


def check_recommendation(content: str) -> None:
    # 문장을 지워 고치면 본문이 비거나 어색해지므로 고치지 않고 거부한다(Spring이 FAILED 처리)
    if _POSITIVE_RECOMMENDATION.search(content):
        raise ForbiddenExpression("recommendation")
