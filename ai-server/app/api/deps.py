from typing import Annotated

from fastapi import Depends, Request

from app.llm.agent import ChatAgent
from app.rag.search import Retriever


def get_agent(request: Request) -> ChatAgent:
    agent: ChatAgent = request.app.state.agent
    return agent


def get_retriever(request: Request) -> Retriever:
    retriever: Retriever = request.app.state.retriever
    return retriever


# 테스트에서는 app.dependency_overrides[get_agent] = ... 로 바꿔 끼운다
AgentDep = Annotated[ChatAgent, Depends(get_agent)]
RetrieverDep = Annotated[Retriever, Depends(get_retriever)]
