import json
import logging

import anthropic
import httpx
import pytest

from app.main import _llm_error


def _status_error(cls: type[anthropic.APIStatusError], status: int, message: str) -> anthropic.APIStatusError:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    response = httpx.Response(status, request=request, json={"error": {"message": message}})
    return cls(message, response=response, body={"error": {"message": message}})


async def test_llm_error_log_has_type_and_status_but_not_sdk_message(caplog: pytest.LogCaptureFixture) -> None:
    echoed = "이전에 성공한 답변 전문이 SDK 오류 문자열에 되돌아온 경우"
    error = _status_error(anthropic.BadRequestError, 400, echoed)

    with caplog.at_level(logging.DEBUG):
        response = await _llm_error(None, error)  # type: ignore[arg-type]

    assert response.status_code == 502
    assert json.loads(bytes(response.body)) == {"error": "LLM_UNAVAILABLE"}
    assert "BadRequestError" in caplog.text
    assert "400" in caplog.text


async def test_llm_error_rate_limit_keeps_its_code(caplog: pytest.LogCaptureFixture) -> None:
    error = _status_error(anthropic.RateLimitError, 429, "slow down")

    response = await _llm_error(None, error)  # type: ignore[arg-type]

    assert json.loads(bytes(response.body)) == {"error": "LLM_RATE_LIMIT"}
