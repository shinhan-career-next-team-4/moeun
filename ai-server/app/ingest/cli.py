"""교육자료·공시 문서를 rag_document / rag_chunk / chunk_embedding에 적재한다.

예) uv run moeun-ingest --file docs/voo.pdf --doc-type DISCLOSURE --org "OO자산운용" \\
        --org-type ASSET_MANAGER --title "OO S&P500 투자설명서" --url https://... --etf-code 360750
"""

import argparse
import asyncio
import hashlib
from pathlib import Path
from typing import Any

import pymupdf
from openai import AsyncOpenAI
from pgvector.psycopg import register_vector_async
from psycopg import AsyncConnection

from app.core.config import get_settings
from app.ingest.chunker import split_chunks
from app.rag.embedder import Embedder

BATCH = 64


def read_text(path: Path) -> str:
    if path.suffix.lower() == ".pdf":
        with pymupdf.open(path) as doc:  # type: ignore[no-untyped-call]
            return "\n\n".join(page.get_text() for page in doc)
    return path.read_text(encoding="utf-8")


async def returning_id(conn: AsyncConnection[tuple[Any, ...]], sql: str, params: tuple[Any, ...]) -> int | None:
    """INSERT ... RETURNING id 실행 후 id를 돌려준다. 충돌로 건너뛰면 None."""
    row = await (await conn.execute(sql, params)).fetchone()
    return None if row is None else int(row[0])


async def ingest(args: argparse.Namespace) -> None:
    s = get_settings()
    text = read_text(Path(args.file))
    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
    chunks = split_chunks(text)
    if not chunks:
        raise SystemExit("본문을 추출하지 못했습니다")

    if not s.openai_api_key:
        raise SystemExit("OPENAI_API_KEY가 필요합니다(.env)")
    embedder = Embedder(AsyncOpenAI(api_key=s.openai_api_key.get_secret_value()), s.embedding_model)
    vectors = []
    for i in range(0, len(chunks), BATCH):
        vectors.extend(await embedder.embed(chunks[i : i + BATCH]))

    async with await AsyncConnection.connect(s.database_url) as conn:
        await register_vector_async(conn)
        print(await store(conn, args, sha, chunks, vectors, embedder.model))


async def store(
    conn: AsyncConnection[tuple[Any, ...]],
    args: argparse.Namespace,
    sha: str,
    chunks: list[str],
    vectors: list[Any],
    model: str,
) -> str:
    """문서·청크·임베딩을 한 트랜잭션으로 넣고 결과 메시지를 돌려준다."""
    async with conn.transaction():
        org_id = await returning_id(
            conn,
            """INSERT INTO source_org (name, org_type) VALUES (%s, %s)
               ON CONFLICT (name) DO UPDATE SET org_type = EXCLUDED.org_type RETURNING id""",
            (args.org, args.org_type),
        )
        doc_id = await returning_id(
            conn,
            """INSERT INTO rag_document (doc_type, source_org_id, title, source_url, content_sha256,
                                         etf_code, published_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s)
               ON CONFLICT (source_url, content_sha256) DO NOTHING RETURNING id""",
            (args.doc_type, org_id, args.title, args.url, sha, args.etf_code, args.published_at),
        )
        first_no = 0
        if doc_id is None:
            if not args.rechunk:
                return "같은 내용의 문서가 이미 있습니다. 건너뜁니다. 청킹 규칙을 바꿨으면 --rechunk로 다시 넣으세요."
            doc_id, first_no = await retire_chunks(conn, args.url, sha, model)
        else:
            # 같은 URL의 이전 개정본은 검색에서 빼되 과거 인용 보존을 위해 행은 남긴다
            await conn.execute(
                """UPDATE chunk_embedding e SET is_active = FALSE
                   FROM rag_chunk c JOIN rag_document d ON d.id = c.document_id
                   WHERE e.chunk_id = c.id AND d.source_url = %s AND d.id <> %s""",
                (args.url, doc_id),
            )
            await conn.execute(
                "UPDATE rag_document SET is_active = FALSE WHERE source_url = %s AND id <> %s", (args.url, doc_id)
            )

        for no, (chunk, vec) in enumerate(zip(chunks, vectors, strict=True), start=first_no):
            chunk_id = await returning_id(
                conn,
                "INSERT INTO rag_chunk (document_id, chunk_no, content) VALUES (%s, %s, %s) RETURNING id",
                (doc_id, no, chunk),
            )
            await conn.execute(
                "INSERT INTO chunk_embedding (chunk_id, model_code, embedding) VALUES (%s, %s, %s)",
                (chunk_id, model, vec),
            )
    return f"document {doc_id}: {len(chunks)} chunks" + (f" (rechunk, chunk_no {first_no}~)" if first_no else "")


async def retire_chunks(conn: AsyncConnection[tuple[Any, ...]], url: str, sha: str, model: str) -> tuple[int, int]:
    """같은 문서를 새 청킹으로 다시 넣기 전에 기존 청크를 검색에서 뺀다. (문서 id, 새 청크의 시작 chunk_no)를 돌려준다.

    과거 인용이 가리키는 청크 행은 남기고 임베딩만 비활성화한다. 새 청크는 chunk_no를 이어 붙인다(uq_rag_chunk_doc_no).
    다른 model_code의 임베딩은 건드리지 않는다(모델 교체 중에도 그 모델의 검색 결과가 비지 않게).
    """
    row = await (
        await conn.execute(
            # 동시에 두 번 돌려도 활성 청크가 두 벌 남지 않게 문서 행을 잠근다
            "SELECT id, is_active FROM rag_document WHERE source_url = %s AND content_sha256 = %s FOR UPDATE",
            (url, sha),
        )
    ).fetchone()
    if row is None:
        raise SystemExit("다시 넣을 문서를 찾지 못했습니다")
    doc_id, is_active = int(row[0]), bool(row[1])
    if not is_active:
        raise SystemExit("이전 개정본입니다. 검색에서 빠진 문서는 다시 청킹하지 않습니다")
    await conn.execute(
        """UPDATE chunk_embedding e SET is_active = FALSE
           FROM rag_chunk c WHERE e.chunk_id = c.id AND c.document_id = %s AND e.model_code = %s AND e.is_active""",
        (doc_id, model),
    )
    last = await (await conn.execute("SELECT max(chunk_no) FROM rag_chunk WHERE document_id = %s", (doc_id,))).fetchone()
    return doc_id, 0 if last is None or last[0] is None else int(last[0]) + 1


def main() -> None:
    p = argparse.ArgumentParser(description="RAG 문서 적재")
    p.add_argument("--file", required=True)
    p.add_argument("--doc-type", required=True, choices=["EDU", "DISCLOSURE"])
    p.add_argument("--org", required=True)
    p.add_argument("--org-type", required=True, choices=["ASSET_MANAGER", "EDU_PROVIDER", "REGULATOR"])
    p.add_argument("--title", required=True)
    p.add_argument("--url", required=True)
    p.add_argument("--etf-code")
    p.add_argument("--published-at", help="YYYY-MM-DD")
    p.add_argument("--rechunk", action="store_true", help="같은 내용의 문서를 새 청킹 규칙으로 다시 넣는다")
    asyncio.run(ingest(p.parse_args()))


if __name__ == "__main__":
    main()
