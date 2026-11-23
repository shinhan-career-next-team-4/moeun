"""#256 /chat이 AgentError로 끝나면 오류 코드만 로그에 남긴다(원인 추적). 본문이 섞인 detail은 남기지 않는다."""

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


async def test_agent_error_log_leaves_out_detail(caplog: pytest.LogCaptureFixture) -> None:
    secret_body = "목표 금액 약 5,000만원을 3년 안에 모으는 계획의 답 본문"
    with caplog.at_level(logging.DEBUG):
        await _agent_error(None, AgentError(ErrorCode.BAD_OUTPUT, secret_body))  # type: ignore[arg-type]

    assert "BAD_OUTPUT" in caplog.text
    assert secret_body not in caplog.text
