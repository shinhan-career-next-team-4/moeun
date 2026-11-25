from fastapi import APIRouter

from app.api.deps import AgentDep
from app.core.security import InternalOnly
from app.schemas import ChatRequest, ChatResponse

router = APIRouter()


@router.post("/chat")
async def chat(req: ChatRequest, agent: AgentDep, _: InternalOnly) -> ChatResponse:
    """Spring이 PENDING 메시지를 만든 뒤 호출한다. 실패 응답이면 Spring이 FAILED로 기록한다."""
    return await agent.run(req)
