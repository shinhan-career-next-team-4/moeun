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

    # 대화 로그 보관(#316). 파일은 자정마다 나누고 이 일수만큼 남긴다. 0이면 파일을 쓰지 않는다
    log_dir: str = "logs"
    log_retention_days: int = 7


@lru_cache
def get_settings() -> Settings:
    return Settings()
