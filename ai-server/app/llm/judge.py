from anthropic import AsyncAnthropic
from anthropic.types.beta import BetaTextBlock, BetaUserMessageParam

from app.llm.prompts import STOCK_JUDGE_PROMPT

_JUDGE_TIMEOUT_SECONDS = 8.0


def _escape_tags(text: str) -> str:
    """본문이 </answer>로 태그를 닫고 판정 지시를 끼워 넣지 못하게 꺾쇠를 전각으로 바꾼다."""
    return text.replace("<", "\uff1c").replace(">", "\uff1e")


class LlmStockJudge:
    """본문이 개별 주식 종목을 추천하는지 모델에게 묻는다(CF-07). 정확히 'false'일 때만 통과시키고,
    true·빈 응답·형식 밖 응답은 모두 종목 추천으로 본다(거부 쪽으로 기운다)."""

    def __init__(self, client: AsyncAnthropic, model: str):
        # 판정은 응답마다 직렬로 최대 2번 더해진다. SDK 자동 재전송을 끄고 타임아웃을 짧게 한다
        # (P-10, Spring read-timeout 30초).
        # 테스트 대역은 with_options가 없어 실제 SDK 클라이언트일 때만 사본을 만든다
        self._client = (
            client.with_options(max_retries=0, timeout=_JUDGE_TIMEOUT_SECONDS)
            if isinstance(client, AsyncAnthropic) else client
        )
        self._model = model

    async def recommends_individual_stock(self, text: str) -> bool:
        resp = await self._client.beta.messages.create(
            model=self._model,
            max_tokens=16,
            system=STOCK_JUDGE_PROMPT,
            messages=[BetaUserMessageParam(role="user", content=f"<answer>{_escape_tags(text)}</answer>")],
        )
        verdict = "".join(b.text for b in resp.content if isinstance(b, BetaTextBlock))
        return verdict.strip(" \t\n.\"'`").lower() != "false"
