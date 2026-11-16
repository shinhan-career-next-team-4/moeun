import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import anthropic
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI

from app.api import chat, health, rag
from app.core.config import force_tracing_off, get_settings
from app.core.db import create_pool
from app.llm.agent import AgentError, ChatAgent, build_chat_model
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.tools.calc import CalcClient

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("moeun.ai")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    force_tracing_off()
    s = get_settings()
    pool = create_pool(s.database_url)
    await pool.open(wait=False)
    spring = httpx.AsyncClient(base_url=s.spring_base_url, timeout=s.spring_timeout_seconds)
    openai = AsyncOpenAI(api_key=s.openai_api_key.get_secret_value()) if s.openai_api_key else None

    retriever = Retriever(pool, Embedder(openai, s.embedding_model), s.rag_top_k)
    app.state.retriever = retriever
    app.state.agent = ChatAgent(
        build_chat_model(model=s.anthropic_model, effort=s.anthropic_effort, api_key=s.anthropic_api_key),
        CalcClient(spring, s.internal_api_key.get_secret_value()),
        retriever,
        max_rounds=s.max_tool_rounds,
    )
    try:
        yield
    finally:
        await spring.aclose()
        if openai:
            await openai.close()
        await pool.close()


app = FastAPI(title="moeun-ai-server", lifespan=lifespan)


@app.exception_handler(AgentError)
async def _agent_error(_: Request, e: AgentError) -> JSONResponse:
    status = 400 if e.code == "BAD_REQUEST" else 502
    return JSONResponse(status_code=status, content={"error": e.code})


@app.exception_handler(anthropic.APIError)
async def _llm_error(_: Request, e: anthropic.APIError) -> JSONResponse:
    log.warning("llm call failed: %s", e)
    code = "LLM_RATE_LIMIT" if isinstance(e, anthropic.RateLimitError) else "LLM_UNAVAILABLE"
    return JSONResponse(status_code=502, content={"error": code})


app.include_router(health.router)
app.include_router(chat.router)
app.include_router(rag.router)
