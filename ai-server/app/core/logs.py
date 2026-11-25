"""로그 설정(#316). 대화가 실패하면 원인을 끝까지 좇을 수 있게 요청·답 본문까지 남긴다. 대신 줄마다 시각을 붙이고,
파일은 자정마다 나눠 보관 기간이 지나면 지운다. API 키·내부 키 같은 비밀값은 우리가 단 핸들러(콘솔·파일)에서 가린다.
uvicorn 로거도 루트로 올려 같은 핸들러·시각·가림을 거친다."""

import logging
from collections.abc import Iterable
from datetime import datetime
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path

from app.core.config import Settings

FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"
FILE_NAME = "ai-server.log"
MASK = "***"
_OWNED = "_moeun_logging"
_UVICORN_LOGGERS = ("uvicorn", "uvicorn.error", "uvicorn.access")


class _IsoTimeFormatter(logging.Formatter):
    """시각은 ISO-8601에 밀리초와 UTC 오프셋까지 쓴다. 보관 기간 판단과 Spring 로그 대조에 쓴다."""

    def formatTime(self, record: logging.LogRecord, datefmt: str | None = None) -> str:
        return datetime.fromtimestamp(record.created).astimezone().isoformat(timespec="milliseconds")


class SecretMask(logging.Filter):
    """완성된 로그 문장에서 비밀값을 가린다. 어떤 로거가 무엇을 남기든 핸들러에서 한 번에 막는다."""

    def __init__(self, secrets: Iterable[str]):
        super().__init__()
        self._secrets = tuple(s for s in secrets if s)

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except (TypeError, ValueError):   # 인자가 틀린 로그 호출. 요청을 깨지 않게 handleError로 넘긴다
            return True
        masked = message
        for secret in self._secrets:
            masked = masked.replace(secret, MASK)
        if masked != message:
            record.msg, record.args = masked, None
        return True


def _secrets(settings: Settings) -> list[str]:
    values = [settings.internal_api_key, settings.anthropic_api_key, settings.openai_api_key]
    return [v.get_secret_value() for v in values if v is not None]


def configure_logging(settings: Settings) -> None:
    """루트 로거에 콘솔과 (보관 기간이 1일 이상이면) 날짜별 파일 핸들러를 단다. 보관 기간 0이면 파일을 쓰지 않는다."""
    handlers: list[logging.Handler] = [logging.StreamHandler()]
    if settings.log_retention_days > 0:
        directory = Path(settings.log_dir)
        directory.mkdir(parents=True, exist_ok=True)
        handlers.append(TimedRotatingFileHandler(
            directory / FILE_NAME, when="midnight", backupCount=settings.log_retention_days, encoding="utf-8",
        ))
    formatter = _IsoTimeFormatter(FORMAT)
    mask = SecretMask(_secrets(settings))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    # 앱이 다시 시작돼도(테스트의 lifespan 등) 핸들러가 쌓이지 않게 전에 단 것을 갈아 끼운다
    for old in [h for h in root.handlers if getattr(h, _OWNED, False)]:
        root.removeHandler(old)
        old.close()
    for handler in handlers:
        handler.setFormatter(formatter)
        handler.addFilter(mask)
        setattr(handler, _OWNED, True)
        root.addHandler(handler)
    # uvicorn은 자기 핸들러로만 쓰고 위로 올리지 않는다. 접근 로그("POST /chat 401")도 날짜별 파일에 남게 루트로 올린다
    for name in _UVICORN_LOGGERS:
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers.clear()
        uvicorn_logger.propagate = True
