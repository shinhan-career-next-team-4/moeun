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

from app.config import get_settings
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
            if doc_id is None:
                print("같은 내용의 문서가 이미 있습니다. 건너뜁니다.")
                return

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

            for no, (chunk, vec) in enumerate(zip(chunks, vectors, strict=True)):
                chunk_id = await returning_id(
                    conn,
                    "INSERT INTO rag_chunk (document_id, chunk_no, content) VALUES (%s, %s, %s) RETURNING id",
                    (doc_id, no, chunk),
                )
                await conn.execute(
                    "INSERT INTO chunk_embedding (chunk_id, model_code, embedding) VALUES (%s, %s, %s)",
                    (chunk_id, embedder.model, vec),
                )
    print(f"document {doc_id}: {len(chunks)} chunks")


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
    asyncio.run(ingest(p.parse_args()))


if __name__ == "__main__":
    main()
