"""Issue #39 수용 기준 테스트: 운용사 섞기(FR-AI-03), 재적재 중복 방지, 이용 허락 게이트(FR-AI-10).

네트워크·DB 없이 fake 풀·fake 커넥션으로 실제 SQL 파라미터와 실행 순서를 검증한다.
allowlist 모듈은 아직 없으므로 import를 각 테스트 안에서 한다(모듈이 없어도 1·2번 테스트는 돈다).
"""

import argparse
from pathlib import Path
from types import SimpleNamespace, TracebackType
from typing import Any, cast

import pytest
import yaml
from fastapi.testclient import TestClient
from psycopg_pool import AsyncConnectionPool
from pydantic import SecretStr

from app.api.deps import get_retriever
from app.core.config import get_settings
from app.ingest import cli
from app.main import app
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.schemas import DocType, RetrievedChunk

# ---------------------------------------------------------------------------
# 1. 검색: 운용사 섞기 (Retriever.search)
# ---------------------------------------------------------------------------


def row(i: int, org: str, doc_type: str = "DISCLOSURE") -> dict[str, Any]:
    return {
        "chunk_id": i, "content": f"청크 {i}", "title": f"문서 {org}", "source_url": f"https://{org}/doc",
        "doc_type": doc_type, "org_name": org, "distance": i / 10,
    }


class FakeCursor:
    def __init__(self, sink: list[tuple[str, dict[str, Any]]], rows: list[dict[str, Any]]):
        self._sink = sink
        self._rows = rows

    async def __aenter__(self) -> "FakeCursor":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    async def execute(self, sql: str, params: dict[str, Any]) -> None:
        self._sink.append((sql, params))

    async def fetchall(self) -> list[dict[str, Any]]:
        return self._rows


class FakeSearchConn:
    def __init__(self, sink: list[tuple[str, dict[str, Any]]], rows: list[dict[str, Any]]):
        self._sink = sink
        self._rows = rows

    async def __aenter__(self) -> "FakeSearchConn":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    def cursor(self, **_: Any) -> FakeCursor:
        return FakeCursor(self._sink, self._rows)


class FakePool:
    """AsyncConnectionPool 자리에 들어가는 fake. 실행된 (SQL, 파라미터)를 executed에 쌓는다."""

    def __init__(self, rows: list[dict[str, Any]]):
        self.executed: list[tuple[str, dict[str, Any]]] = []
        self._rows = rows

    def connection(self) -> FakeSearchConn:
        return FakeSearchConn(self.executed, self._rows)


class FakeEmbedder:
    model = "fake-embedding-model"

    def __init__(self) -> None:
        self.queries: list[list[str]] = []

    async def embed(self, texts: list[str]) -> list[list[float]]:
        self.queries.append(texts)
        return [[0.5, 0.5] for _ in texts]


def make_retriever(rows: list[dict[str, Any]], top_k: int = 5) -> tuple[Retriever, FakePool, FakeEmbedder]:
    pool, embedder = FakePool(rows), FakeEmbedder()
    retriever = Retriever(cast(AsyncConnectionPool, pool), cast(Embedder, embedder), top_k)
    return retriever, pool, embedder


async def test_disclosure_search_mixes_orgs_keeping_distance_order():
    """AC1: 'ETF 위험' 공시 검색에서 후보 뒤쪽의 다른 운용사 문서가 상위에 섞인다 (FR-AI-03)."""
    rows = [row(1, "A운용"), row(2, "A운용"), row(3, "A운용"), row(4, "A운용"), row(5, "A운용"), row(6, "B운용")]
    retriever, _, embedder = make_retriever(rows)

    got = await retriever.search("ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert embedder.queries == [["ETF 위험"]]
    assert len(got) == 5
    assert {c.org_name for c in got} == {"A운용", "B운용"}
    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 6]
    assert [c.distance for c in got] == sorted(c.distance for c in got)


@pytest.mark.parametrize("doc_type", [DocType.EDU, None])
async def test_non_disclosure_search_does_not_mix_orgs(doc_type):
    """AC1 경계: 교육자료(EDU)나 doc_type 미지정이면 운용사를 섞지 않고 상위 k만 돌려준다."""
    rows = [row(i, "A운용", "EDU") for i in range(1, 6)] + [row(6, "B운용", "EDU")]
    retriever, _, _ = make_retriever(rows)

    got = await retriever.search("ETF 위험", doc_type, top_k=5)

    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 5]
    assert {c.org_name for c in got} == {"A운용"}


async def test_disclosure_search_returns_single_org_when_candidates_have_only_one():
    """AC1 알려진 한계(docs/rag-eval.md): 후보에 운용사가 한 곳뿐이면 한 곳만 나온다. 재검색을 넣으면 이 테스트를 고친다."""
    rows = [row(i, "A운용") for i in range(1, 7)]
    retriever, _, _ = make_retriever(rows)

    got = await retriever.search("ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 5]
    assert {c.org_name for c in got} == {"A운용"}


async def test_search_sql_params_and_invariants():
    """rag.md 불변 조건: limit = k*4, model = 임베더 모델, 활성 조건 3개(부분 HNSW 인덱스)가 SQL에 있다."""
    retriever, pool, embedder = make_retriever([row(1, "A운용")])

    await retriever.search("ETF 위험", DocType.DISCLOSURE, top_k=5)

    [(sql, params)] = pool.executed
    flat = " ".join(sql.split())
    assert params["limit"] == 20
    assert params["model"] == embedder.model
    assert params["doc_type"] == DocType.DISCLOSURE
    assert "e.is_active AND e.model_code = %(model)s AND d.is_active" in flat


async def test_search_uses_default_top_k_and_passes_no_doc_type_filter():
    """top_k를 안 주면 생성자 기본값을 쓰고, doc_type이 없으면 SQL 필터 파라미터가 None이다."""
    rows = [row(i, "A운용") for i in range(1, 11)]
    retriever, pool, _ = make_retriever(rows, top_k=3)

    got = await retriever.search("ETF 위험")

    assert pool.executed[0][1]["limit"] == 12
    assert pool.executed[0][1]["doc_type"] is None
    assert len(got) == 3


# ---------------------------------------------------------------------------
# 1. 라우터: POST /rag/search
# ---------------------------------------------------------------------------


class FakeRouterRetriever:
    def __init__(self) -> None:
        self.calls: list[tuple[str, DocType | None, int | None]] = []

    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        self.calls.append((query, doc_type, top_k))
        return [
            RetrievedChunk(chunk_id=1, content="환율 위험", title="KODEX 투자설명서", source_url="https://a/doc",
                           doc_type=DocType.DISCLOSURE, org_name="삼성자산운용", distance=0.1),
            RetrievedChunk(chunk_id=2, content="지수 하락 위험", title="TIGER 투자설명서", source_url="https://b/doc",
                           doc_type=DocType.DISCLOSURE, org_name="미래에셋자산운용", distance=0.2),
        ]


def internal_headers() -> dict[str, str]:
    return {"X-Internal-Api-Key": get_settings().internal_api_key.get_secret_value()}


def test_rag_search_requires_internal_key():
    """AC1: 내부 키가 없거나 틀리면 401이고 검색은 호출되지 않는다."""
    fake = FakeRouterRetriever()
    app.dependency_overrides[get_retriever] = lambda: fake
    try:
        client = TestClient(app)
        no_key = client.post("/rag/search", json={"query": "ETF 위험"})
        wrong = client.post("/rag/search", headers={"X-Internal-Api-Key": "wrong"}, json={"query": "ETF 위험"})
    finally:
        app.dependency_overrides.clear()
    assert no_key.status_code == 401
    assert wrong.status_code == 401
    assert fake.calls == []


def test_rag_search_returns_camel_case_with_distinct_orgs_and_forwards_request_fields():
    """AC1: 200 응답은 camelCase이고 서로 다른 orgName이 섞여 있으며, docType·topK가 search로 전달된다."""
    fake = FakeRouterRetriever()
    app.dependency_overrides[get_retriever] = lambda: fake
    try:
        client = TestClient(app)
        r = client.post(
            "/rag/search", headers=internal_headers(), json={"query": "ETF 위험", "docType": "DISCLOSURE", "topK": 5}
        )
    finally:
        app.dependency_overrides.clear()

    assert r.status_code == 200
    body = r.json()
    assert fake.calls == [("ETF 위험", DocType.DISCLOSURE, 5)]
    assert set(body[0]) == {"chunkId", "content", "title", "sourceUrl", "docType", "orgName", "distance"}
    assert body[0]["docType"] == "DISCLOSURE"
    assert {c["orgName"] for c in body} == {"삼성자산운용", "미래에셋자산운용"}


def test_rag_search_without_optional_fields_passes_none():
    """docType·topK를 생략하면 None으로 전달된다(서버 기본값을 쓰게 한다)."""
    fake = FakeRouterRetriever()
    app.dependency_overrides[get_retriever] = lambda: fake
    try:
        r = TestClient(app).post("/rag/search", headers=internal_headers(), json={"query": "ETF 위험"})
    finally:
        app.dependency_overrides.clear()
    assert r.status_code == 200
    assert fake.calls == [("ETF 위험", None, None)]


@pytest.mark.parametrize("payload", [{"query": ""}, {"query": "ETF", "topK": 0}, {"query": "ETF", "topK": 21}])
def test_rag_search_rejects_invalid_request(payload):
    """예외: 빈 질의, 범위 밖 topK는 422이고 검색을 호출하지 않는다."""
    fake = FakeRouterRetriever()
    app.dependency_overrides[get_retriever] = lambda: fake
    try:
        r = TestClient(app).post("/rag/search", headers=internal_headers(), json=payload)
    finally:
        app.dependency_overrides.clear()
    assert r.status_code == 422
    assert fake.calls == []


# ---------------------------------------------------------------------------
# 2. 적재: 같은 문서 재적재 시 중복 없음 (cli.store)
# ---------------------------------------------------------------------------

URL = "https://example.com/prospectus.pdf"
MODEL = "text-embedding-3-small"


class FakeResult:
    def __init__(self, rows: list[tuple[Any, ...]]):
        self._rows = rows

    async def fetchone(self) -> tuple[Any, ...] | None:
        return self._rows[0] if self._rows else None

    async def fetchall(self) -> list[tuple[Any, ...]]:
        return self._rows


class FakeTransaction:
    def __init__(self, conn: "FakeStoreConn"):
        self._conn = conn

    async def __aenter__(self) -> None:
        self._conn.depth += 1

    async def __aexit__(
        self, exc_type: type[BaseException] | None, exc: BaseException | None, tb: TracebackType | None
    ) -> None:
        self._conn.depth -= 1


class FakeStoreConn:
    """실행된 SQL·파라미터를 기록하고, SQL 앞부분으로 골라 미리 정한 행을 돌려준다."""

    def __init__(self, *, existing_doc: bool, doc_is_active: bool = True, last_chunk_no: int | None = None):
        self.executed: list[tuple[str, tuple[Any, ...], bool]] = []   # (정규화한 SQL, 파라미터, 트랜잭션 안 여부)
        self.depth = 0
        self._existing_doc = existing_doc
        self._doc_is_active = doc_is_active
        self._last_chunk_no = last_chunk_no
        self._chunk_seq = 100

    def transaction(self) -> FakeTransaction:
        return FakeTransaction(self)

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> FakeResult:
        flat = " ".join(sql.split())
        self.executed.append((flat, params, self.depth > 0))
        if flat.startswith("INSERT INTO source_org"):
            return FakeResult([(1,)])
        if flat.startswith("INSERT INTO rag_document"):
            return FakeResult([] if self._existing_doc else [(10,)])   # 충돌(DO NOTHING)이면 RETURNING 행이 없다
        if flat.startswith("SELECT id, is_active FROM rag_document"):
            return FakeResult([(10, self._doc_is_active)])
        if flat.startswith("SELECT max(chunk_no)"):
            return FakeResult([(self._last_chunk_no,)])
        if flat.startswith("INSERT INTO rag_chunk"):
            self._chunk_seq += 1
            return FakeResult([(self._chunk_seq,)])
        if flat.startswith("SELECT count(*)"):
            return FakeResult([(0,)])   # 정합성 검사: 문제 없음
        return FakeResult([])

    def sql_starting(self, prefix: str) -> list[tuple[str, tuple[Any, ...], bool]]:
        return [e for e in self.executed if e[0].startswith(prefix)]


def store_args(rechunk: bool = False) -> argparse.Namespace:
    return argparse.Namespace(
        org="삼성자산운용", org_type="ASSET_MANAGER", doc_type="DISCLOSURE", title="KODEX 투자설명서", url=URL,
        etf_code=None, published_at=None, rechunk=rechunk,
    )


async def run_store(conn: FakeStoreConn, rechunk: bool = False) -> str:
    return await cli.store(cast(Any, conn), store_args(rechunk), "sha-1", ["청크 하나", "청크 둘"], [[0.1], [0.2]], MODEL)


async def test_reingest_same_document_is_skipped_without_inserting_chunks():
    """AC2: 같은 (url, sha) 문서를 rechunk 없이 다시 넣으면 건너뛰고 청크·임베딩을 만들지 않는다."""
    conn = FakeStoreConn(existing_doc=True)

    message = await run_store(conn, rechunk=False)

    assert "건너뜁니다" in message
    assert "ON CONFLICT (source_url, content_sha256) DO NOTHING" in conn.sql_starting("INSERT INTO rag_document")[0][0]
    assert conn.sql_starting("INSERT INTO rag_chunk") == []
    assert conn.sql_starting("INSERT INTO chunk_embedding") == []
    assert conn.sql_starting("UPDATE") == []


async def test_rechunk_retires_only_current_model_embeddings_and_continues_chunk_no():
    """AC2: --rechunk는 현재 model_code의 활성 임베딩만 끄고 chunk_no를 마지막+1부터 이어 붙인다 (rag.md)."""
    conn = FakeStoreConn(existing_doc=True, last_chunk_no=4)

    message = await run_store(conn, rechunk=True)

    [(update_sql, update_params, _)] = conn.sql_starting("UPDATE")
    assert update_sql.startswith("UPDATE chunk_embedding e SET is_active = FALSE")
    assert "e.model_code = %s" in update_sql and "e.is_active" in update_sql
    assert update_params == (10, MODEL)   # 문서 id와 현재 model_code만. 다른 모델·다른 문서는 건드리지 않는다
    assert conn.sql_starting("UPDATE rag_document") == []   # 같은 문서는 비활성화하지 않는다
    assert [p[1] for _, p, _ in conn.sql_starting("INSERT INTO rag_chunk")] == [5, 6]
    assert [p[1] for _, p, _ in conn.sql_starting("INSERT INTO chunk_embedding")] == [MODEL, MODEL]
    assert "chunk_no 5~" in message


async def test_rechunk_retires_before_inserting_new_chunks():
    """AC2: 기존 임베딩을 끄는 UPDATE가 새 청크 INSERT보다 먼저 실행된다(활성 청크가 두 벌 남지 않게)."""
    conn = FakeStoreConn(existing_doc=True, last_chunk_no=0)

    await run_store(conn, rechunk=True)

    sqls = [e[0] for e in conn.executed]
    first_update = next(i for i, s in enumerate(sqls) if s.startswith("UPDATE chunk_embedding"))
    first_insert = next(i for i, s in enumerate(sqls) if s.startswith("INSERT INTO rag_chunk"))
    assert first_update < first_insert


async def test_rechunk_of_retired_revision_is_refused():
    """AC2 예외: 이미 검색에서 빠진 이전 개정본은 rechunk해도 새 청크를 넣지 않고 중단한다."""
    conn = FakeStoreConn(existing_doc=True, doc_is_active=False, last_chunk_no=4)

    with pytest.raises(SystemExit):
        await run_store(conn, rechunk=True)

    assert conn.sql_starting("INSERT INTO rag_chunk") == []
    assert conn.sql_starting("INSERT INTO chunk_embedding") == []


async def test_new_revision_deactivates_previous_revisions_of_same_url_in_one_transaction():
    """AC2: 새 문서(개정본)면 같은 URL의 이전 임베딩·문서를 같은 트랜잭션에서 비활성화하고 chunk_no는 0부터 시작한다."""
    conn = FakeStoreConn(existing_doc=False)

    message = await run_store(conn)

    [(emb_sql, emb_params, _)] = conn.sql_starting("UPDATE chunk_embedding")
    [(doc_sql, doc_params, _)] = conn.sql_starting("UPDATE rag_document")
    assert "SET is_active = FALSE" in emb_sql and "d.source_url = %s AND d.id <> %s" in emb_sql
    assert emb_params == (URL, 10)
    assert doc_sql == "UPDATE rag_document SET is_active = FALSE WHERE source_url = %s AND id <> %s"
    assert doc_params == (URL, 10)
    assert [p[1] for _, p, _ in conn.sql_starting("INSERT INTO rag_chunk")] == [0, 1]
    assert all(in_tx for _, _, in_tx in conn.executed)
    assert message == "document 10: 2 chunks"


# ---------------------------------------------------------------------------
# 3. 이용 허락 게이트: app/ingest/allowlist.py (FR-AI-10)
# ---------------------------------------------------------------------------

ALLOWED = {"url": URL, "org": "삼성자산운용", "org_type": "ASSET_MANAGER", "doc_type": "DISCLOSURE"}
EDU_ALLOWED = {"url": "https://edu.example/guide", "org": "금융교육원", "org_type": "EDU_PROVIDER", "doc_type": "EDU"}


def write_allowlist(tmp_path: Path, sources: list[dict[str, str]]) -> Path:
    path = tmp_path / "allowlist.yml"
    path.write_text(yaml.safe_dump({"sources": sources}, allow_unicode=True), encoding="utf-8")
    return path


def test_load_allowlist_reads_sources(tmp_path):
    """AC3: YAML의 sources 항목을 AllowedSource 목록으로 읽는다."""
    from app.ingest.allowlist import AllowedSource, load_allowlist

    got = load_allowlist(write_allowlist(tmp_path, [ALLOWED, EDU_ALLOWED]))

    assert got == [AllowedSource(**ALLOWED), AllowedSource(**EDU_ALLOWED)]


def test_load_allowlist_missing_file_raises(tmp_path):
    """AC3 예외: 목록 파일이 없으면 적재를 허락하지 않는다(NotLicensedError)."""
    from app.ingest.allowlist import NotLicensedError, load_allowlist

    with pytest.raises(NotLicensedError):
        load_allowlist(tmp_path / "없음.yml")


@pytest.mark.parametrize(
    "content",
    [
        "",                                                                           # 빈 파일
        "sources: [unclosed",                                                         # YAML 문법 오류
        "- url: https://a\n",                                                         # 최상위가 목록
        "other: []\n",                                                                # sources 키 없음
        "sources: not-a-list\n",                                                      # sources가 목록이 아님
        "sources:\n  - url: https://a\n    org: A\n    org_type: ASSET_MANAGER\n",   # doc_type 키 없음
        "sources:\n  - just-a-string\n",                                              # 항목이 매핑이 아님
    ],
)
def test_load_allowlist_malformed_raises(tmp_path, content):
    """AC3 예외: 형식이 틀린 목록은 NotLicensedError."""
    from app.ingest.allowlist import NotLicensedError, load_allowlist

    path = tmp_path / "allowlist.yml"
    path.write_text(content, encoding="utf-8")

    with pytest.raises(NotLicensedError):
        load_allowlist(path)


def test_check_allowed_passes_on_exact_match():
    """AC3: (url, org, org_type, doc_type)가 모두 일치하는 항목이 있으면 통과한다."""
    from app.ingest.allowlist import AllowedSource, check_allowed

    allowlist = [AllowedSource(**EDU_ALLOWED), AllowedSource(**ALLOWED)]

    assert check_allowed(allowlist, **ALLOWED) is None
    assert check_allowed(allowlist, **EDU_ALLOWED) is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("url", "https://example.com/other.pdf"),
        ("org", "다른자산운용"),
        ("org_type", "REGULATOR"),
        ("doc_type", "EDU"),
    ],
)
def test_check_allowed_rejects_any_field_mismatch(field, value):
    """AC3: 네 값 중 하나라도 다르면 허락 없는 자료다."""
    from app.ingest.allowlist import AllowedSource, NotLicensedError, check_allowed

    with pytest.raises(NotLicensedError):
        check_allowed([AllowedSource(**ALLOWED)], **{**ALLOWED, field: value})


def test_check_allowed_rejects_when_list_is_empty():
    """AC3 예외: 빈 목록이면 어떤 자료도 허락되지 않는다."""
    from app.ingest.allowlist import NotLicensedError, check_allowed

    with pytest.raises(NotLicensedError):
        check_allowed([], **ALLOWED)


@pytest.mark.parametrize("org_type", ["EDU_PROVIDER", "REGULATOR"])
def test_check_allowed_rejects_disclosure_not_from_asset_manager_even_if_listed(org_type):
    """AC3: 공시(DISCLOSURE)는 운용사(ASSET_MANAGER) 자료만. 목록에 있어도 거부한다."""
    from app.ingest.allowlist import AllowedSource, NotLicensedError, check_allowed

    entry = {**ALLOWED, "org_type": org_type}

    with pytest.raises(NotLicensedError):
        check_allowed([AllowedSource(**entry)], **entry)


# ---------------------------------------------------------------------------
# 3. CLI 통합: cli.ingest는 허락을 가장 먼저 검사한다
# ---------------------------------------------------------------------------


class CliHarness:
    """cli.ingest의 외부 의존(파일 읽기, 임베딩, DB, store)을 fake로 바꾸고 호출 순서를 events에 남긴다."""

    def __init__(self, monkeypatch: pytest.MonkeyPatch):
        self.events: list[str] = []
        self.stored_args: list[argparse.Namespace] = []
        self.stored_model: list[str] = []
        events = self.events
        real_read_text = cli.read_text

        def read_text(path: Path) -> str:
            events.append("read")
            return real_read_text(path)

        class FakeEmbedder:
            def __init__(self, client: object, model: str):
                self.model = model

            async def embed(self, texts: list[str]) -> list[list[float]]:
                events.append("embed")
                return [[0.0] for _ in texts]

        class FakeCliConn:
            async def __aenter__(self) -> "FakeCliConn":
                return self

            async def __aexit__(self, *exc: object) -> None:
                return None

        class FakeAsyncConnection:
            @staticmethod
            async def connect(url: str) -> FakeCliConn:
                events.append("connect")
                return FakeCliConn()

        async def register_vector_async(conn: object) -> None:
            return None

        async def store(
            conn: object, args: argparse.Namespace, sha: str, chunks: list[str], vectors: list[Any], model: str
        ) -> str:
            events.append("store")
            self.stored_args.append(args)
            self.stored_model.append(model)
            return "stored"

        settings = SimpleNamespace(
            openai_api_key=SecretStr("sk-test"), embedding_model="fake-model", database_url="postgresql://fake"
        )
        monkeypatch.setattr(cli, "get_settings", lambda: settings)
        monkeypatch.setattr(cli, "read_text", read_text)
        monkeypatch.setattr(cli, "Embedder", FakeEmbedder)
        monkeypatch.setattr(cli, "AsyncOpenAI", lambda api_key: object())
        monkeypatch.setattr(cli, "AsyncConnection", FakeAsyncConnection)
        monkeypatch.setattr(cli, "register_vector_async", register_vector_async)
        monkeypatch.setattr(cli, "store", store)


def ingest_args(tmp_path: Path, allowlist: Path, **overrides: Any) -> argparse.Namespace:
    doc = tmp_path / "doc.txt"
    doc.write_text("ETF 위험에 대한 설명 문단입니다. 원금 손실이 날 수 있습니다.", encoding="utf-8")
    values: dict[str, Any] = {
        "file": str(doc), "doc_type": "DISCLOSURE", "org": "삼성자산운용", "org_type": "ASSET_MANAGER",
        "title": "KODEX 투자설명서", "url": URL, "etf_code": None, "published_at": None, "rechunk": False,
        "allowlist": str(allowlist),
    }
    return argparse.Namespace(**{**values, **overrides})


async def test_ingest_with_permission_reaches_store_after_check(tmp_path, monkeypatch):
    """AC3: 허락된 자료는 읽기 → 임베딩 → DB 연결 → store까지 간다."""
    harness = CliHarness(monkeypatch)
    args = ingest_args(tmp_path, write_allowlist(tmp_path, [ALLOWED]))

    await cli.ingest(args)

    assert harness.events == ["read", "embed", "connect", "store"]
    assert harness.stored_args == [args]
    assert harness.stored_model == ["fake-model"]


@pytest.mark.parametrize(
    "overrides",
    [
        {"url": "https://example.com/unlisted.pdf"},   # 목록에 없는 URL
        {"org": "다른자산운용"},                          # 운용사 불일치
        {"doc_type": "EDU"},                           # 문서 유형 불일치
    ],
)
async def test_ingest_without_permission_exits_before_read_embed_connect_store(tmp_path, monkeypatch, overrides):
    """AC3: 허락 없는 자료는 SystemExit이고 읽기·임베딩·DB 연결·store가 한 번도 호출되지 않는다."""
    harness = CliHarness(monkeypatch)
    args = ingest_args(tmp_path, write_allowlist(tmp_path, [ALLOWED]), **overrides)

    with pytest.raises(SystemExit):
        await cli.ingest(args)

    assert harness.events == []


async def test_ingest_with_missing_allowlist_file_exits_before_anything(tmp_path, monkeypatch):
    """AC3 예외: 목록 파일이 없으면 적재하지 않는다."""
    harness = CliHarness(monkeypatch)
    args = ingest_args(tmp_path, tmp_path / "없음.yml")

    with pytest.raises(SystemExit):
        await cli.ingest(args)

    assert harness.events == []


async def test_ingest_with_malformed_allowlist_exits_before_anything(tmp_path, monkeypatch):
    """AC3 예외: 목록 형식이 틀리면 적재하지 않는다."""
    harness = CliHarness(monkeypatch)
    bad = tmp_path / "allowlist.yml"
    bad.write_text("sources: not-a-list\n", encoding="utf-8")
    args = ingest_args(tmp_path, bad)

    with pytest.raises(SystemExit):
        await cli.ingest(args)

    assert harness.events == []


async def test_ingest_disclosure_from_non_asset_manager_exits_even_if_listed(tmp_path, monkeypatch):
    """AC3: 공시인데 운용사가 아니면 목록에 있어도 적재하지 않는다."""
    harness = CliHarness(monkeypatch)
    entry = {**ALLOWED, "org_type": "EDU_PROVIDER"}
    args = ingest_args(tmp_path, write_allowlist(tmp_path, [entry]), org_type="EDU_PROVIDER")

    with pytest.raises(SystemExit):
        await cli.ingest(args)

    assert harness.events == []

