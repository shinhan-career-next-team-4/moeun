"""SDK 루프(ChatAgent)용 모델·검색 대역. 응답을 차례로 돌려주고 받은 요청을 남긴다."""

import json
from types import SimpleNamespace
from typing import Any

from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.schemas import DocType, RetrievedChunk


def text_resp(message_type: str, content: str = "답", citations: list[int] | None = None) -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": citations or []}
    text = json.dumps(payload, ensure_ascii=False)
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=text)])


def raw_resp(text: str, stop_reason: str = "end_turn") -> SimpleNamespace:
    return SimpleNamespace(stop_reason=stop_reason, content=[BetaTextBlock(type="text", text=text)])


def tool_resp(name: str, input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name=name, input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[SimpleNamespace]):
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> SimpleNamespace:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeRetriever:
    def __init__(self, chunks: list[RetrievedChunk] | None = None):
        self.chunks = chunks or []

    async def search(
        self, query: str, doc_type: DocType | None = None, top_k: int | None = None, etf_codes: Any = None
    ) -> list[RetrievedChunk]:
        return self.chunks


class PassJudge:
    """개별 주식 종목 추천이 없다고 판정하는 대역."""

    async def recommends_individual_stock(self, text: str) -> bool:
        return False
