import re
from typing import Protocol

GENERIC_ETF_NAME = "S&P500 추종 ETF"
# 본문에 써도 되는 ETF 표현(#155, CP-06 수정). 계획 종목 정합성 검사와 교정 지시의 대체 예가 이 상수를 읽는다
ALLOWED_ETF_NAMES = ("코스피200 추종 ETF", GENERIC_ETF_NAME, "나스닥 추종 ETF")

# 개별 주식 종목 추천만 금지한다(CF-07, D-028). ETF 추천 표현과 ETF 상품명은 거부·치환하지 않는다.
# 종목명(삼성전자·삼전 등)은 열린 집합이라 규칙으로 가릴 수 없어, 본문에 추천 낱말이 있을 때만 판정 모델에 묻는다.
# 활용형을 따지지 않고 낱말로 거른다. 부정문("추천하지 않아요")도 판정에 넘기면 모델이 가려낸다.
# 프롬프트가 막는 '사라고 권하는 표현'(권합니다·권장해요·사세요·매수·담아 보세요)도 같이 거른다
_RECOMMENDATION_WORDS = re.compile(r"추천|권[유장하해합]|매수|사세요|사라고|담아\s*보세요|담으세요")


# 본문에 display 키 이름(diff.AVG.afterTaxAssetDelta, current.AVG.afterTaxAsset, AVG.gapToGoal 등)이 그대로 나오면
# 사용자에게 내부 이름이 보인다(NFR-USA-01, #57). 키는 값(문자열)만 인용하고 이름은 쓰지 않는다
_DISPLAY_KEY = re.compile(r"\b(?:current|changed|diff|conditions|assumptions)\.[A-Za-z]|\b(?:GOOD|AVG|BAD)\.[A-Za-z]")


class ForbiddenExpression(Exception):
    """본문에 나가면 안 되는 표현이 있다. 호출자가 AgentError로 바꿔 올린다."""


class StockRecommendationJudge(Protocol):
    async def recommends_individual_stock(self, text: str) -> bool:
        """본문이 개별 주식 종목을 추천하면 True. ETF 추천·상품명 언급·추천하지 않는다는 말은 False."""
        ...


def needs_stock_judgment(content: str) -> bool:
    """판정 모델을 부를 본문인가. 추천 낱말이 없으면 종목 추천일 수 없어 부르지 않는다(응답마다 호출이 늘지 않게)."""
    return _RECOMMENDATION_WORDS.search(content) is not None


async def check_stock_recommendation(content: str, judge: StockRecommendationJudge) -> None:
    # 문장을 지워 고치면 본문이 비거나 어색해지므로 고치지 않고 거부한다(Spring이 FAILED 처리)
    if needs_stock_judgment(content) and await judge.recommends_individual_stock(content):
        raise ForbiddenExpression("stock_recommendation")


# 문장 경계: .·!·? 뒤(공백이 없어도), 줄바꿈. '15.4%'처럼 뒤에 숫자가 이어지는 소수점은 경계가 아니다
_SENTENCE_BREAK = re.compile(r"((?<=[.!?])(?!\d)\s*|\n+)")


def _sentences(content: str) -> list[tuple[str, str]]:
    """(문장, 뒤따르는 구분 공백) 목록. 이어 붙이면 원문이 된다."""
    parts = _SENTENCE_BREAK.split(content)
    return list(zip(parts[0::2], [*parts[1::2], ""], strict=True))


def check_display_keys(content: str) -> None:
    if _DISPLAY_KEY.search(content):
        raise ForbiddenExpression("internal_key")


def names_etf(content: str) -> bool:
    """본문에 허용 ETF 표현이 있다. 종목 표시값이 없는 ETF 0% 계획의 검사에 쓴다(#155, P-4).
    ETF 상품명(브랜드)은 허용 정책(#168, CF-07)이라 여기서도 보지 않는다."""
    return any(name in content for name in ALLOWED_ETF_NAMES)


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
