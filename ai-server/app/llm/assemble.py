import re
from typing import Protocol

# 개별 주식 종목 추천만 금지한다(CF-07, D-028). ETF 추천 표현과 ETF 상품명은 거부·치환하지 않는다.
# 종목명(삼성전자·삼전 등)은 열린 집합이라 규칙으로 가릴 수 없어, 본문에 추천 낱말이 있을 때만 판정 모델에 묻는다.
# 활용형을 따지지 않고 낱말로 거른다. 부정문("추천하지 않아요")도 판정에 넘기면 모델이 가려낸다.
# 프롬프트가 막는 '사라고 권하는 표현'(권합니다·권장해요·사세요·매수·담아 보세요)도 같이 거른다
_RECOMMENDATION_WORDS = re.compile(r"추천|권[유장하해합]|매수|사세요|사라고|담아\s*보세요|담으세요")


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
