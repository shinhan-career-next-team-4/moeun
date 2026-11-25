"""#256 /chat이 AgentError로 끝나면 오류 코드를 로그에 남긴다(원인 추적). 상세도 남기는 정책은 #316(tests/chat_log)."""

import json
import logging

import pytest

from app.core.errors import ErrorCode
from app.llm.errors import AgentError
from app.main import _agent_error


@pytest.mark.parametrize(
    ("code", "status"),
    [(ErrorCode.NUMBER_MISMATCH, 502), (ErrorCode.FORBIDDEN_EXPRESSION, 502), (ErrorCode.BAD_REQUEST, 400)],
)
async def test_agent_error_logs_its_code(caplog: pytest.LogCaptureFixture, code: ErrorCode, status: int) -> None:
    with caplog.at_level(logging.WARNING):
        response = await _agent_error(None, AgentError(code))  # type: ignore[arg-type]

    assert response.status_code == status
    assert json.loads(bytes(response.body)) == {"error": code}
    assert [r for r in caplog.records if r.levelno == logging.WARNING and code in r.getMessage()]

