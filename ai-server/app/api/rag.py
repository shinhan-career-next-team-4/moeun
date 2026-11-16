from fastapi import APIRouter

from app.api.deps import RetrieverDep
from app.core.security import InternalOnly
from app.schemas import RetrievedChunk, SearchRequest

router = APIRouter()


@router.post("/rag/search")
async def rag_search(req: SearchRequest, retriever: RetrieverDep, _: InternalOnly) -> list[RetrievedChunk]:
    return await retriever.search(req.query, req.doc_type, req.top_k)
