import numpy as np
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from app.rag.embedder import Embedder
from app.schemas import DocType, RetrievedChunk

# 부분 HNSW 인덱스(WHERE is_active)를 타도록 e.is_active 조건을 반드시 둔다
_SQL = """
SELECT c.id AS chunk_id, c.content, d.title, d.source_url, d.doc_type, o.name AS org_name,
       e.embedding <=> %(q)s AS distance
FROM chunk_embedding e
JOIN rag_chunk c    ON c.id = e.chunk_id
JOIN rag_document d ON d.id = c.document_id
JOIN source_org o   ON o.id = d.source_org_id
WHERE e.is_active AND e.model_code = %(model)s AND d.is_active
  AND (%(doc_type)s::text IS NULL OR d.doc_type = %(doc_type)s)
  AND (%(exclude_orgs)s::text[] IS NULL OR o.name <> ALL(%(exclude_orgs)s::text[]))
ORDER BY e.embedding <=> %(q)s
LIMIT %(limit)s
"""


def diversify_orgs(rows: list[RetrievedChunk], top_k: int, min_orgs: int = 2) -> list[RetrievedChunk]:
    """공시 근거는 서로 다른 운용사 2곳 이상이어야 한다(FR-AI-03). 거리순을 유지하며 출처를 섞는다."""
    picked = rows[:top_k]
    if len({r.org_name for r in picked}) >= min_orgs:
        return picked
    seen = {r.org_name for r in picked}
    for r in rows[top_k:]:
        if r.org_name not in seen:
            picked = [*picked[:-1], r] if len(picked) >= top_k else [*picked, r]
            break
    return picked


class Retriever:
    def __init__(self, pool: AsyncConnectionPool, embedder: Embedder, top_k: int):
        self._pool = pool
        self._embedder = embedder
        self._top_k = top_k

    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        k = top_k or self._top_k
        [vec] = await self._embedder.embed([query])
        rows = await self._fetch(vec, doc_type, k * 4)
        if doc_type != DocType.DISCLOSURE or k < 2:
            # 운용사 2곳은 결과가 2개 이상일 때만 가능하다. top_k=1은 최인접 chunk를 그대로 돌려준다
            return rows[:k]
        orgs = {r.org_name for r in rows}
        if len(orgs) == 1:
            # 후보가 한 운용사로 쏠렸다. 그 운용사를 뺀 같은 조건 검색을 한 번만 더 한다(FR-AI-03)
            seen = {r.chunk_id for r in rows}
            rows += [r for r in await self._fetch(vec, doc_type, k * 4, sorted(orgs)) if r.chunk_id not in seen]
        return diversify_orgs(rows, k)

    async def _fetch(
        self, vec: np.ndarray, doc_type: DocType | None, limit: int, exclude_orgs: list[str] | None = None
    ) -> list[RetrievedChunk]:
        async with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            await cur.execute(
                _SQL,
                {
                    "q": vec,
                    "model": self._embedder.model,
                    "doc_type": doc_type,
                    "limit": limit,
                    "exclude_orgs": exclude_orgs,
                },
            )
            return [RetrievedChunk(**r) for r in await cur.fetchall()]
