"""#316 대화 로그 정책: 원인 추적을 위해 요청·도구 입출력·모델 답·실패 상세를 남기고, 시각을 붙여 며칠만 보관한다.
비밀값(API 키·내부 키)은 남기지 않는다. 이 정책이 P-7·#256·C1a-8의 "본문을 남기지 않는다"를 대체한다."""

import json
import logging
import re
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path

import anthropic
import httpx
import pytest

from app.core.config import Settings
from app.core.errors import ErrorCode
from app.core.logs import configure_logging
from app.llm.agent import ChatAgent
from app.llm.errors import AgentError
from app.main import _agent_error, _llm_error
from app.schemas import ChatRequest
from tests.fakes import FakeCalc
from tests.fakes.claude import FakeClaude, FakeRetriever, PassJudge, text_resp, tool_resp
from tests.paths import REPO

PLAN_CONTEXT = json.loads(
    (REPO / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json").read_text(encoding="utf-8")
)
MESSAGE_ID = 4316


def _agent(claude: FakeClaude, calc: FakeCalc | None = None, max_rounds: int = 4) -> ChatAgent:
    return ChatAgent(
        claude, calc or FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium",
        max_rounds=max_rounds, stock_judge=PassJudge(),
    )


def _question(text: str) -> ChatRequest:
    return ChatRequest(
        memberId=1, messageId=MESSAGE_ID, mode="QUESTION", text=text, baselineScenario="AVG",
        planContext=PLAN_CONTEXT, history=[{"role": "assistant", "content": "어떤 금액을 바꿀까요?"}],
    )


def _messages(caplog: pytest.LogCaptureFixture) -> str:
    return "\n".join(r.getMessage() for r in caplog.records)


# ---- 대화 추적 로그 ----

async def test_logs_request_tool_io_and_answer_with_message_id(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO)
    claude = FakeClaude([
        tool_resp("recalculate", {"monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "월 50만원으로 바꾼 결과를 정리했어요."),
    ])
    calc = FakeCalc()

    await _agent(claude, calc).run(_question("월 50만원으로 하면 어때요?"))

    logged = [r.getMessage() for r in caplog.records if f"message_id={MESSAGE_ID}" in r.getMessage()]
    text = "\n".join(logged)
    assert "월 50만원으로 하면 어때요?" in text              # 받은 요청 text
    assert "어떤 금액을 바꿀까요?" in text                   # history
    assert "약 1,712만원" in text                            # planContext display
    assert "recalculate" in text and "500000" in text       # 도구 이름·입력
    assert '"calcRunId"' in text                            # 도구 결과(계산 응답)
    assert "월 50만원으로 바꾼 결과를 정리했어요." in text    # 최종 답


async def test_too_many_rounds_detail_lists_tools_called(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO)
    claude = FakeClaude([tool_resp("recalculate", {"goal_amount": 100_000_000}, f"tu_{i}") for i in range(2)])

    with pytest.raises(AgentError) as err:
        await _agent(claude, max_rounds=2).run(_question("모으는 돈이요"))

    assert err.value.code == ErrorCode.TOO_MANY_ROUNDS
    assert err.value.detail == "recalculate,recalculate"
    assert f"message_id={MESSAGE_ID}" in _messages(caplog) and "TOO_MANY_ROUNDS" in _messages(caplog)


async def test_number_mismatch_detail_and_rejected_answer_are_logged(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO)
    body = "목표를 9억원으로 바꾸면 평균일 때 약 1,712만원이 돼요."
    claude = FakeClaude([text_resp("ANSWER", body)])

    with pytest.raises(AgentError) as err:
        await _agent(claude).run(_question("어떻게 돼요?"))

    assert err.value.code == ErrorCode.NUMBER_MISMATCH
    assert "9억원" in err.value.detail
    assert body in _messages(caplog)   # 거절된 모델 답 본문


# ---- 실패 핸들러 ----

async def test_agent_error_handler_logs_code_and_detail(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.WARNING):
        response = await _agent_error(None, AgentError(ErrorCode.NUMBER_MISMATCH, "9억원"))  # type: ignore[arg-type]

    assert json.loads(bytes(response.body)) == {"error": "NUMBER_MISMATCH"}   # Spring에는 코드만 간다
    assert "NUMBER_MISMATCH" in caplog.text and "9억원" in caplog.text


async def test_llm_error_handler_logs_sdk_message(caplog: pytest.LogCaptureFixture) -> None:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    message = "prompt is too long"
    response = httpx.Response(400, request=request, json={"error": {"message": message}})
    error = anthropic.BadRequestError(message, response=response, body={"error": {"message": message}})

    with caplog.at_level(logging.WARNING):
        await _llm_error(None, error)  # type: ignore[arg-type]

    assert message in caplog.text and "BadRequestError" in caplog.text


# ---- 보관·시각·비밀값 ----

@pytest.fixture
def configured(tmp_path: Path):
    root = logging.getLogger()
    before = list(root.handlers)
    settings = Settings(
        log_dir=str(tmp_path), log_retention_days=7,
        anthropic_api_key="sk-ant-secret-key-316", internal_api_key="internal-secret-316",
    )
    configure_logging(settings)
    yield tmp_path
    for handler in root.handlers[:]:
        if handler not in before:
            root.removeHandler(handler)
            handler.close()


def test_file_log_rotates_daily_and_keeps_retention_days(configured: Path) -> None:
    files = [h for h in logging.getLogger().handlers if isinstance(h, TimedRotatingFileHandler)]
    assert len(files) == 1
    assert files[0].when == "MIDNIGHT" and files[0].backupCount == 7
    assert Path(files[0].baseFilename).parent == configured


def test_file_log_lines_start_with_iso_time_with_offset(configured: Path) -> None:
    logging.getLogger("moeun.ai").warning("chat failed: code=TEST")
    for handler in logging.getLogger().handlers:
        handler.flush()

    line = next(p for p in configured.iterdir()).read_text(encoding="utf-8").splitlines()[-1]
    assert re.match(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d\d:\d\d ", line), line
    assert "chat failed: code=TEST" in line


def test_secrets_are_masked_in_file_log(configured: Path) -> None:
    logging.getLogger("moeun.ai").warning("headers key=%s internal=%s", "sk-ant-secret-key-316", "internal-secret-316")
    for handler in logging.getLogger().handlers:
        handler.flush()

    content = next(p for p in configured.iterdir()).read_text(encoding="utf-8")
    assert "sk-ant-secret-key-316" not in content and "internal-secret-316" not in content
    assert "key=***" in content


def test_retention_zero_turns_off_file_log(tmp_path: Path) -> None:
    root = logging.getLogger()
    before = list(root.handlers)
    configure_logging(Settings(log_dir=str(tmp_path / "off"), log_retention_days=0))
    try:
        added = [h for h in root.handlers if h not in before]
        assert not any(isinstance(h, TimedRotatingFileHandler) for h in added)
        assert not (tmp_path / "off").exists()
    finally:
        for handler in root.handlers[:]:
            if handler not in before:
                root.removeHandler(handler)
