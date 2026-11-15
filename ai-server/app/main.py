import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

import anthropic
import httpx
from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI

from app.config import get_settings
from app.db import create_pool
from app.llm.agent import AgentError, ChatAgent
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.schemas import ChatRequest, ChatResponse, RetrievedChunk, SearchRequest
from app.security import InternalOnly
from app.tools.calc import CalcClient

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("moeun.ai")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    s = get_settings()
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


def get_agent(request: Request) -> ChatAgent:
    agent: ChatAgent = request.app.state.agent
    return agent


def get_retriever(request: Request) -> Retriever:
    retriever: Retriever = request.app.state.retriever
    return retriever


# 테스트에서는 app.dependency_overrides[get_agent] = ... 로 바꿔 끼운다
AgentDep = Annotated[ChatAgent, Depends(get_agent)]
RetrieverDep = Annotated[Retriever, Depends(get_retriever)]

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


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/chat")
async def chat(req: ChatRequest, agent: AgentDep, _: InternalOnly) -> ChatResponse:
    """Spring이 PENDING 메시지를 만든 뒤 호출한다. 실패 응답이면 Spring이 FAILED로 기록한다."""
    return await agent.run(req)


@app.post("/rag/search")
async def rag_search(req: SearchRequest, retriever: RetrieverDep, _: InternalOnly) -> list[RetrievedChunk]:
    return await retriever.search(req.query, req.doc_type, req.top_k)
