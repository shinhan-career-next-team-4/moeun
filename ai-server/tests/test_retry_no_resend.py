"""교정 재시도 호출은 SDK 자동 재전송을 하지 않는다(P-10, AC-6): 두 번째 요청이 실패하면 세 번째 전송 없이 끝난다."""

import json
from typing import Any

import anthropic
import httpx2  # anthropic SDK가 쓰는 HTTP 클라이언트
import pytest

from tests.test_forbidden_retry import RECOMMEND, make_agent, question, sdk_message


def _sdk_answer(content: str) -> dict[str, Any]:
    body = {"message_type": "ANSWER", "content": content, "citation_chunk_ids": []}
    return sdk_message(json.dumps(body, ensure_ascii=False))


async def test_retry_call_api_error_is_not_resent_by_sdk() -> None:
    """기본 재시도 설정의 AsyncAnthropic: 금지 답 → HTTP 500이면 APIError가 올라가고 전송은 2번이다"""
    responses = [
        httpx2.Response(200, json=_sdk_answer(RECOMMEND)),
        httpx2.Response(500, json={"type": "error", "error": {"type": "api_error", "message": "boom"}}),
        httpx2.Response(200, json=_sdk_answer("깨끗한 답이에요")),
    ]
    sent: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        return responses.pop(0)

    async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
        client = anthropic.AsyncAnthropic(api_key="test-key", base_url="http://anthropic.test", http_client=http)
        with pytest.raises(anthropic.APIError):
            await make_agent(client).run(question())
    assert len(sent) == 2

