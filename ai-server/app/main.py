import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import anthropic
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI

from app.api import chat, health, rag
from app.core.config import get_settings
from app.core.db import create_pool
from app.core.errors import ErrorCode
from app.core.logs import configure_logging
from app.llm.agent import AgentError, ChatAgent
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.tools.calc import CalcClient

log = logging.getLogger("moeun.ai")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    s = get_settings()
    configure_logging(s)
    pool = create_pool(s.database_url)
    await pool.open(wait=False)
    spring = httpx.AsyncClient(base_url=s.spring_base_url, timeout=s.spring_timeout_seconds)
    claude = anthropic.AsyncAnthropic(api_key=s.anthropic_api_key.get_secret_value() if s.anthropic_api_key else None)
    openai = AsyncOpenAI(api_key=s.openai_api_key.get_secret_value()) if s.openai_api_key else None

    retriever = Retriever(pool, Embedder(openai, s.embedding_model), s.rag_top_k)
    app.state.retriever = retriever
    app.state.agent = ChatAgent(
        claude,
        CalcClient(spring, s.internal_api_key.get_secret_value()),
        retriever,
        model=s.anthropic_model,
        effort=s.anthropic_effort,
        max_rounds=s.max_tool_rounds,
    )
    try:
        yield
    finally:
        await spring.aclose()
        await claude.close()
        if openai:
            await openai.close()
        await pool.close()


app = FastAPI(title="moeun-ai-server", lifespan=lifespan)


# 요청이 잘못돼 400으로 돌려줄 코드. 나머지 AgentError는 502다
BAD_REQUEST_CODES = frozenset({ErrorCode.BAD_REQUEST})


@app.exception_handler(AgentError)
async def _agent_error(_: Request, e: AgentError) -> JSONResponse:
    status = 400 if e.code in BAD_REQUEST_CODES else 502
    # 원인 추적용으로 코드와 상세(걸린 숫자·도구 순서 등)를 남긴다. Spring에는 코드만 돌려준다(#316)
    reason = getattr(e, "reason", None)
    log.warning(
        "chat failed: code=%s status=%s%s detail=%s", e.code, status, f" reason={reason}" if reason else "", e.detail
    )
    return JSONResponse(status_code=status, content={"error": e.code})


@app.exception_handler(anthropic.APIError)
async def _llm_error(_: Request, e: anthropic.APIError) -> JSONResponse:
    # 원인 추적용으로 SDK 오류 문장까지 남긴다(#316). 비밀값은 로그 핸들러가 가린다
    log.warning("llm call failed: type=%s status=%s message=%s", type(e).__name__, getattr(e, "status_code", None), e)
    code = ErrorCode.LLM_RATE_LIMIT if isinstance(e, anthropic.RateLimitError) else ErrorCode.LLM_UNAVAILABLE
    return JSONResponse(status_code=502, content={"error": code})


app.include_router(health.router)
app.include_router(chat.router)
app.include_router(rag.router)
