import os
from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

Effort = Literal["low", "medium", "high", "xhigh", "max"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql://moeun:moeun@localhost:5432/moeun"
    spring_base_url: str = "http://localhost:8080"
    internal_api_key: SecretStr = SecretStr("local-internal-key")
    spring_timeout_seconds: float = 10.0

    anthropic_api_key: SecretStr | None = None
    anthropic_model: str = "claude-opus-5-5"
    anthropic_effort: Effort = "medium"
    max_tool_rounds: int = 6

    openai_api_key: SecretStr | None = None
    embedding_model: str = "text-embedding-3-small"   # chunk_embedding.model_code 값으로도 쓴다
    rag_top_k: int = 5


@lru_cache
def get_settings() -> Settings:
    return Settings()


def force_tracing_off() -> None:
    """외부 추적(LangSmith)을 끈다. LangChain은 .env가 아니라 프로세스 환경 변수를 읽는다(.claude/rules/langchain.md)."""
    # langsmith는 LANGSMITH_·LANGCHAIN_ 접두사와 TRACING_V2·TRACING 이름을 모두 읽는다
    for prefix in ("LANGSMITH", "LANGCHAIN"):
        for name in ("TRACING_V2", "TRACING"):
            os.environ[f"{prefix}_{name}"] = "false"
