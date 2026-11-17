import re

GENERIC_ETF_NAME = "S&P500 추종 ETF"

# 본문에 상품명을 쓰지 않는다(D-028, NFR-USA-02). 출처 제목은 원래 이름 그대로 두므로 모델 본문에만 건다.
# 영문 브랜드는 대소문자 구분 없이, 한글 표기(코덱스 등)도 함께 본다.
# 'Tigers' 같은 영단어는 걸리지 않게 앞뒤 영문자를 막는다.
# RISE·PLUS·SOL·ACE는 일반 영단어와 겹쳐 대문자 표기에 미국·S&P·ETF·숫자가 이어질 때만 상품명으로 본다.
# 아리랑·하나로는 일상어라 한글 표기는 넣지 않았다
_BRAND = (
    r"(?<![A-Za-z])(?:(?i:KODEX|TIGER|KBSTAR|ARIRANG|HANARO)|코덱스|타이거|케이비스타"
    r"|(?:RISE|KOSEF|PLUS|SOL|ACE)(?=\s*(?:미국|S&P|ETF|\d)))(?![A-Za-z])"
)
# 브랜드 뒤에 S&P500 지수 이름이 이어지면 상품명 전체를 한 번에 바꾼다
_SP500_SUFFIX = r"(?P<sp>\s*(?:미국\s*)?(?i:S&P)\s*500(?:\s*(?:TR|지수))?(?:\s*(?i:ETF))?)?"
_PRODUCT_NAME = re.compile(_BRAND + _SP500_SUFFIX)

# 긍정 추천 활용형만 본다. "추천하지 않아요"·"추천드리지 않아요"는 활용형이 달라 걸리지 않는다(CF-07)
_POSITIVE_RECOMMENDATION = re.compile(
    r"추천\s*(?:(?:해\s*)?드(?:려요|립니다|릴게요|릴께요|리겠습니다)|해요|합니다|할게요|할께요|하겠습니다)"
)


class ForbiddenExpression(Exception):
    """본문에 나가면 안 되는 표현이 있다. 호출자가 AgentError로 바꿔 올린다."""


def replace_product_names(content: str) -> str:
    """'KODEX 미국S&P500'처럼 S&P500 상품명이면 이름 전체를 'S&P500 추종 ETF'로 바꾼다.
    브랜드만 있으면 어떤 지수인지 알 수 없어(KODEX 200 등) 바꾸면 사실과 달라지므로 거부한다."""
    def replace(m: re.Match[str]) -> str:
        if m.group("sp") is None:
            raise ForbiddenExpression("product_name")
        return GENERIC_ETF_NAME

    return _PRODUCT_NAME.sub(replace, content)


def check_recommendation(content: str) -> None:
    # 문장을 지워 고치면 본문이 비거나 어색해지므로 고치지 않고 거부한다(Spring이 FAILED 처리)
    if _POSITIVE_RECOMMENDATION.search(content):
        raise ForbiddenExpression("recommendation")
