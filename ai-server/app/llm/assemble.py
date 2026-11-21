import re

GENERIC_ETF_NAME = "S&P500 추종 ETF"
# 본문에 써도 되는 ETF 표현(#155, CP-06 수정). 상품명 검사와 교정 지시의 대체 예가 모두 이 상수를 읽는다
ALLOWED_ETF_NAMES = ("코스피200 추종 ETF", GENERIC_ETF_NAME, "나스닥 추종 ETF")

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


def _allowed_spans(content: str) -> list[tuple[int, int]]:
    """허용 ETF 표현이 본문에서 차지하는 구간. 상수를 호출 시점에 읽어 항목을 더하면 바로 반영된다."""
    names = sorted(ALLOWED_ETF_NAMES, key=len, reverse=True)
    return [m.span() for m in re.finditer("|".join(map(re.escape, names)), content)] if names else []


def replace_product_names(content: str, etf_name: str | None = None) -> str:
    """'KODEX 미국S&P500'처럼 S&P500 상품명이면 이름 전체를 'S&P500 추종 ETF'로 바꾼다.
    브랜드만 있으면 어떤 지수인지 알 수 없어(KODEX 200 등) 바꾸면 사실과 달라지므로 거부한다.
    계획의 종목 표시값(etf_name)이 S&P500 추종 ETF가 아니면 S&P500 상품명이 계획 종목인지 다른 상품인지 알 수 없어
    어느 이름으로 바꿔도 사실과 달라질 수 있으므로 같은 이유로 거부한다(#155).
    허용 ETF 표현 안의 글자(예: 'ACE 미국 지수 추종 ETF')는 상품명으로 보지 않는다."""
    allowed = _allowed_spans(content)

    def replace(m: re.Match[str]) -> str:
        if any(start <= m.start() and m.end() <= end for start, end in allowed):
            return m.group(0)
        if m.group("sp") is None or etf_name not in (None, GENERIC_ETF_NAME):
            raise ForbiddenExpression("product_name")
        return GENERIC_ETF_NAME

    return _PRODUCT_NAME.sub(replace, content)


# 문장 경계: .·!·? 뒤(공백이 없어도), 줄바꿈. '15.4%'처럼 뒤에 숫자가 이어지는 소수점은 경계가 아니다
_SENTENCE_BREAK = re.compile(r"((?<=[.!?])(?!\d)\s*|\n+)")


def _sentences(content: str) -> list[tuple[str, str]]:
    """(문장, 뒤따르는 구분 공백) 목록. 이어 붙이면 원문이 된다."""
    parts = _SENTENCE_BREAK.split(content)
    return list(zip(parts[0::2], [*parts[1::2], ""], strict=True))


def names_etf(content: str) -> bool:
    """본문에 브랜드 상품명이나 허용 ETF 표현이 있다. 종목 표시값이 없는 ETF 0% 계획의 검사에 쓴다(#155, P-4)."""
    return _PRODUCT_NAME.search(content) is not None or any(name in content for name in ALLOWED_ETF_NAMES)


# 계획을 말하는 문장 신호. 이 신호가 있는 문장이 계획 종목 표시값 없이 다른 허용 ETF 표현만 쓰면 종목을 바꿔 말한 것이다
_PLAN_REFERENCE = ("계획", "이 종목", "그 종목", "해당 종목", "내 종목")


def check_plan_etf_claims(content: str, etf_name: str | None) -> None:
    """표시값이 있는 계획에서 계획이나 계획 종목('이 종목' 등)을 말하는 문장이 다른 허용 ETF 표현만 쓰면
    계획 종목을 바꿔 말한 것이라 거부한다. 다른 ETF를 따로 설명하는 문장은 허용한다(#155)."""
    if etf_name is None:
        return
    others = [name for name in ALLOWED_ETF_NAMES if name != etf_name]
    for sentence, _ in _sentences(content):
        if etf_name not in sentence and any(x in sentence for x in _PLAN_REFERENCE) and any(x in sentence for x in others):
            raise ForbiddenExpression("product_name")


def check_recommendation(content: str) -> None:
    # 문장을 지워 고치면 본문이 비거나 어색해지므로 고치지 않고 거부한다(Spring이 FAILED 처리)
    if _POSITIVE_RECOMMENDATION.search(content):
        raise ForbiddenExpression("recommendation")
