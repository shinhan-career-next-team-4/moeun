"""Issue #183: 검색에 etf_code 필터를 둬 다른 종목 근거가 섞이지 않게 한다(FR-AI-03, FR-AI-10, CP-06).

네트워크·DB 없이 fake 풀과 fake 모델로 SQL 파라미터와 검색 호출 인자를 본다.
"""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock
from psycopg_pool import AsyncConnectionPool

from app.llm.agent import ChatAgent
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.schemas import ChatRequest, DocType, RetrievedChunk

PLAN_DIR = Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "plan-context"

# ---------------------------------------------------------------------------
# 1. 검색: Retriever.search(etf_codes=...)
# ---------------------------------------------------------------------------


def row(i: int, org: str) -> dict[str, Any]:
    return {
        "chunk_id": i, "content": f"청크 {i}", "title": f"문서 {org}", "source_url": f"https://{org}/doc",
        "doc_type": "DISCLOSURE", "org_name": org, "distance": i / 10,
    }


class FakeCursor:
    def __init__(self, sink: list[tuple[str, dict[str, Any]]], rows: list[dict[str, Any]]):
        self._sink, self._rows = sink, rows

    async def __aenter__(self) -> "FakeCursor":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    async def execute(self, sql: str, params: dict[str, Any]) -> None:
        self._sink.append((sql, params))

    async def fetchall(self) -> list[dict[str, Any]]:
        return self._rows


class FakeConn:
    def __init__(self, sink: list[tuple[str, dict[str, Any]]], rows: list[dict[str, Any]]):
        self._sink, self._rows = sink, rows

    async def __aenter__(self) -> "FakeConn":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    def cursor(self, **_: Any) -> FakeCursor:
        return FakeCursor(self._sink, self._rows)


class FakePool:
    def __init__(self, rows: list[dict[str, Any]]):
        self.executed: list[tuple[str, dict[str, Any]]] = []
        self._rows = rows

    def connection(self) -> FakeConn:
        return FakeConn(self.executed, self._rows)


class FakeEmbedder:
    model = "fake-embedding-model"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [[0.5, 0.5] for _ in texts]


def make_retriever(rows: list[dict[str, Any]], top_k: int = 5) -> tuple[Retriever, FakePool]:
    pool = FakePool(rows)
    return Retriever(cast(AsyncConnectionPool, pool), cast(Embedder, FakeEmbedder()), top_k), pool


async def test_etf_code_filter_goes_to_sql_and_keeps_active_invariants():
    """AC1: etf_codes가 SQL 파라미터로 가고 `d.etf_code = ANY(...)` 조건이 있다. 활성 조건 3개는 그대로다(rag.md)."""
    retriever, pool = make_retriever([row(1, "삼성")])

    await retriever.search("코스피200 ETF 위험", DocType.DISCLOSURE, etf_codes=["069500"])

    [(sql, params)] = pool.executed
    flat = " ".join(sql.split())
    assert params["etf_codes"] == ["069500"]
    assert "d.etf_code = ANY(%(etf_codes)s::text[])" in flat
    assert "e.is_active AND e.model_code = %(model)s AND d.is_active" in flat


async def test_etf_code_filter_skips_org_diversification_for_single_org_result():
    """AC1: 종목 필터가 걸리면 운용사가 1곳이어도 그대로 돌려주고 운용사 보충 검색을 하지 않는다."""
    rows = [row(i, "삼성") for i in range(1, 8)]
    retriever, pool = make_retriever(rows)

    got = await retriever.search("코스피200 ETF 위험", DocType.DISCLOSURE, top_k=5, etf_codes=["069500"])

    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 5]
    assert len(pool.executed) == 1   # 운용사 제외 재검색이 없다


async def test_etf_code_filter_does_not_pull_other_org_from_outside_the_filter():
    """AC1: 필터 안에 운용사가 2곳이면(S&P500) 거리순 그대로 상위 k를 준다. 섞기 위해 순서를 바꾸지 않는다."""
    rows = [row(1, "삼성"), row(2, "삼성"), row(3, "삼성"), row(4, "삼성"), row(5, "삼성"), row(6, "미래에셋")]
    retriever, _ = make_retriever(rows)

    got = await retriever.search("S&P500 ETF 위험", DocType.DISCLOSURE, top_k=5, etf_codes=["379800", "360750"])

    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 5]


async def test_no_filter_keeps_org_diversification_and_passes_none():
    """AC2: 필터가 없으면 기존대로 운용사 2곳 이상을 섞고, SQL 파라미터는 None이다."""
    rows = [row(1, "A운용"), row(2, "A운용"), row(3, "A운용"), row(4, "A운용"), row(5, "A운용"), row(6, "B운용")]
    retriever, pool = make_retriever(rows)

    got = await retriever.search("ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert {c.org_name for c in got} == {"A운용", "B운용"}
    assert pool.executed[0][1]["etf_codes"] is None


async def test_etf_code_without_documents_returns_empty():
    """AC5(예외): 문서가 없는 종목 코드는 다른 종목 문서로 채우지 않고 빈 결과다."""
    retriever, pool = make_retriever([])

    got = await retriever.search("없는 종목 위험", DocType.DISCLOSURE, etf_codes=["999999"])

    assert got == []
    assert [params["etf_codes"] for _, params in pool.executed] == [["999999"]]


# ---------------------------------------------------------------------------
# 2. 종목 코드 확보: 상품 코드 -> 문서 코드, 표시 이름 -> 상품 코드
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("product", "doc_codes"),
    [("SP500", ["379800", "360750"]), ("069500", ["069500"]), ("133690", ["133690"])],
)
def test_product_code_maps_to_document_etf_codes(product, doc_codes):
    """백엔드 종목 코드(SP500)와 문서의 etf_code(S&P500은 KODEX 379800, TIGER 360750)가 다르다."""
    from app.rag.etf_codes import doc_codes_for

    assert doc_codes_for([product]) == doc_codes


def test_unknown_product_code_is_dropped():
    from app.rag.etf_codes import doc_codes_for

    assert doc_codes_for(["XXXX"]) == []
    assert doc_codes_for(["069500", "XXXX"]) == ["069500"]


@pytest.mark.parametrize(
    ("name", "product"),
    [
        ("S&P500 추종 ETF", "SP500"),
        ("코스피200 추종 ETF", "069500"),
        ("나스닥100 추종 ETF", "133690"),
        ("알 수 없는 ETF", None),
        (None, None),
    ],
)
def test_product_from_display_name(name, product):
    from app.rag.etf_codes import product_from_display_name

    assert product_from_display_name(name) == product


# ---------------------------------------------------------------------------
# 3. 에이전트: 질문이 계획보다 우선, 계획이 폴백, 둘 다 없으면 필터 없음
# ---------------------------------------------------------------------------


def text_resp(payload: dict[str, Any]) -> SimpleNamespace:
    block = BetaTextBlock(type="text", text=json.dumps(payload))
    return SimpleNamespace(stop_reason="end_turn", content=[block])


def tool_resp(input_: dict[str, Any]) -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id="tu_1", name="search_documents", input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[SimpleNamespace]):
        self.responses = list(responses)
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> SimpleNamespace:
        return self.responses.pop(0)


class FakeCalc:
    async def recalculate(self, *_: Any) -> dict[str, Any]:
        raise AssertionError("계산하지 않는다")


class RecordingRetriever:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None, **kw: Any):
        self.calls.append(kw)
        return [
            RetrievedChunk(chunk_id=11, content="본문", title="제목", source_url="u", doc_type=DocType.DISCLOSURE,
                           org_name="삼성", distance=0.1)
        ]


def plan_with_product(name: str | None) -> dict[str, Any]:
    plan = json.loads((PLAN_DIR / "plan-context-basic.json").read_text(encoding="utf-8"))
    if name is None:
        plan["display"].pop("assumptions.etfProductName", None)
    else:
        plan["display"]["assumptions.etfProductName"] = name
    return plan


async def run_search(
    tool_input: dict[str, Any], plan: dict[str, Any] | None, answer_ids: list[int] | None = None
) -> RecordingRetriever:
    retriever = RecordingRetriever()
    answer = {"message_type": "ANSWER", "content": "설명", "citation_chunk_ids": answer_ids or [11]}
    claude = FakeClaude([tool_resp(tool_input), text_resp(answer)])
    agent = ChatAgent(claude, FakeCalc(), retriever, model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text="이 ETF 위험이 뭐야?", baselineScenario="AVG",
                      planContext=plan)
    await agent.run(req)
    return retriever


BASE_INPUT = {"query": "ETF 위험", "doc_type": "DISCLOSURE"}


async def test_plan_product_is_used_only_when_model_points_at_my_etf():
    """결정2: 질문이 내 계획의 ETF를 가리키면(PLAN) 계획의 종목으로 건다. 코드는 서버가 계획에서 읽는다."""
    retriever = await run_search({**BASE_INPUT, "etf_codes": ["PLAN"]}, plan_with_product("코스피200 추종 ETF"))

    assert retriever.calls == [{"etf_codes": ["069500"]}]


async def test_general_question_is_not_filtered_even_when_plan_has_a_product():
    """AC2: 계획에 종목이 있어도 종목을 가리키지 않는 일반 질문(null)은 필터 없이 기존대로 검색한다."""
    retriever = await run_search({**BASE_INPUT, "etf_codes": None}, plan_with_product("코스피200 추종 ETF"))

    assert retriever.calls == [{}]


@pytest.mark.parametrize("doc_type", ["EDU", "ANY"])
async def test_non_disclosure_search_is_never_filtered_by_product(doc_type):
    """교육자료는 etf_code 없이 적재된다. 필터를 걸면 개념 질문의 근거가 0건이 되므로 공시 검색에만 건다."""
    tool_input = {"query": "복리가 뭐야", "doc_type": doc_type, "etf_codes": ["PLAN", "069500"]}

    retriever = await run_search(tool_input, plan_with_product("코스피200 추종 ETF"))

    assert retriever.calls == [{}]


async def test_question_product_wins_over_plan_product():
    """AC3·결정3: 질문에 다른 종목이 명시되면 계획의 종목보다 질문이 이긴다."""
    retriever = await run_search({**BASE_INPUT, "etf_codes": ["133690"]}, plan_with_product("코스피200 추종 ETF"))

    assert retriever.calls == [{"etf_codes": ["133690"]}]


async def test_comparison_question_filters_both_products():
    """AC4·결정4: 비교 질문은 두 종목을 함께 건다."""
    retriever = await run_search(
        {**BASE_INPUT, "etf_codes": ["069500", "133690"]}, plan_with_product("S&P500 추종 ETF")
    )

    assert retriever.calls == [{"etf_codes": ["069500", "133690"]}]


async def test_sp500_product_expands_to_both_issuer_documents():
    """백엔드 코드 SP500은 문서 etf_code 두 개(KODEX, TIGER)로 펼쳐진다."""
    retriever = await run_search({**BASE_INPUT, "etf_codes": ["PLAN"]}, plan_with_product("S&P500 추종 ETF"))

    assert retriever.calls == [{"etf_codes": ["379800", "360750"]}]


async def test_plan_pointer_without_a_plan_product_is_not_filtered():
    """PLAN이라고 했지만 계획이 없거나 종목을 알 수 없으면 필터 없이 검색한다."""
    for plan in (None, plan_with_product(None)):
        retriever = await run_search({**BASE_INPUT, "etf_codes": ["PLAN"]}, plan)

        assert retriever.calls == [{}]


async def test_unknown_model_product_is_ignored_not_trusted():
    """모델이 정한 코드는 검증한다. 모르는 코드는 버려 필터가 걸리지 않는다."""
    retriever = await run_search({**BASE_INPUT, "etf_codes": ["XXXX"]}, plan_with_product("나스닥100 추종 ETF"))

    assert retriever.calls == [{}]


class MixedRetriever:
    """필터 검색과 필터 없는 검색을 한 요청에서 모두 받는 fake. 필터 없는 쪽은 운용사 둘을 돌려준다."""

    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None, **kw: Any):
        if kw.get("etf_codes"):
            return [RetrievedChunk(chunk_id=11, content="코스피200", title="KODEX 200", source_url="u1",
                                   doc_type=DocType.DISCLOSURE, org_name="삼성", distance=0.1)]
        return [
            RetrievedChunk(chunk_id=21, content="일반", title="KODEX S&P", source_url="u2",
                           doc_type=DocType.DISCLOSURE, org_name="삼성", distance=0.1),
            RetrievedChunk(chunk_id=22, content="나스닥", title="TIGER 나스닥100", source_url="u3",
                           doc_type=DocType.DISCLOSURE, org_name="미래에셋", distance=0.2),
        ]


async def test_filtered_turn_does_not_add_other_product_chunk_for_org_coverage():
    """한 요청에서 종목 필터 검색을 했으면 운용사 2곳 보충(_cover_orgs)으로 다른 종목 청크를 더하지 않는다."""
    answer = {"message_type": "ANSWER", "content": "설명", "citation_chunk_ids": [21]}
    claude = FakeClaude([
        tool_resp({**BASE_INPUT, "etf_codes": ["069500"]}),
        tool_resp({**BASE_INPUT, "etf_codes": None}),
        text_resp(answer),
    ])
    agent = ChatAgent(claude, FakeCalc(), MixedRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text="코스피200 위험이 뭐야?", baselineScenario="AVG")

    resp = await agent.run(req)

    assert resp.citation_chunk_ids == [21]   # 22(다른 종목, 다른 운용사)가 보충되지 않는다
