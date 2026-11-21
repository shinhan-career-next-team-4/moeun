"""Issue #40: ETF 위험 설명의 근거 인용은 서로 다른 운용사 2곳 이상, 이번 요청에서 검색된 chunk만.

FR-AI-03, FR-AI-10, D-028.

네트워크·DB 없이 FakeClaude / FakeRetriever / 가짜 풀로 검증한다. 모델이 프롬프트를 지키는지는 가짜 모델로
보장할 수 없으므로 수동 확인 몫이다(spec.md "수동 확인").
"""

import json
from types import SimpleNamespace
from typing import Any, cast

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock
from psycopg_pool import AsyncConnectionPool

from app.llm.agent import MAX_CITATIONS, ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.schemas import ChatRequest, DocType, RetrievedChunk

ETF_RISK_QUESTION = "S&P500 ETF의 위험이 뭐예요?"
SEARCH_DISCLOSURE = {"query": "S&P500 추종 ETF 위험", "doc_type": "DISCLOSURE"}


# ---- fake (다른 테스트 파일과 묶이지 않도록 따로 둔다) ----

def text_resp(
    message_type: str, citations: list[int], content: str = "S&P500 추종 ETF는 가격이 떨어질 수 있어요"
) -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": citations}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(name: str, input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name=name, input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[SimpleNamespace]):
        self.responses = list(responses)
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> SimpleNamespace:
        return self.responses.pop(0)


class FakeCalc:
    async def recalculate(self, member_id: int, baseline: Any, changes: Any) -> dict[str, Any]:
        raise AssertionError("ETF 위험 설명은 계산을 부르지 않는다")


class FakeRetriever:
    def __init__(self, chunks: list[RetrievedChunk]):
        self.chunks = chunks
        self.calls = 0

    async def search(
        self, query: str, doc_type: DocType | None = None, top_k: int | None = None
    ) -> list[RetrievedChunk]:
        self.calls += 1
        return self.chunks


def chunk(chunk_id: int, org: str, doc_type: DocType = DocType.DISCLOSURE) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id, content=f"청크 {chunk_id}", title=f"문서 {org}", source_url=f"https://{org}/doc",
        doc_type=doc_type, org_name=org, distance=chunk_id / 1000,
    )


def make_agent(claude: FakeClaude, retriever: FakeRetriever) -> ChatAgent:
    return ChatAgent(claude, FakeCalc(), retriever, model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text: str = ETF_RISK_QUESTION) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG")


def orgs_of(ids: list[int], chunks: list[RetrievedChunk]) -> set[str]:
    by_id = {c.chunk_id: c.org_name for c in chunks}
    return {by_id[i] for i in ids}


# ---- AC-1: ETF 위험 답은 서로 다른 운용사 chunk를 2개 이상 인용한다 ----

async def test_citations_gain_one_chunk_from_another_org_when_model_cites_a_single_org():
    """검색 결과에 두 운용사가 있는데 모델이 A운용만 인용하면 서버가 가장 가까운 B운용 chunk 하나를 더한다 (FR-AI-03)"""
    found = [chunk(101, "A운용"), chunk(102, "A운용"), chunk(103, "B운용"), chunk(104, "B운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("ANSWER", [101, 102])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.message_type == "ANSWER"
    assert resp.citation_chunk_ids == [101, 102, 103]


async def test_citations_gain_other_org_chunk_when_model_cites_only_one_chunk():
    """모델이 chunk 하나만 인용해도 결과의 운용사는 2곳이다. 모델이 고른 chunk가 앞에 남는다"""
    found = [chunk(101, "A운용"), chunk(102, "A운용"), chunk(103, "B운용"), chunk(104, "B운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("ANSWER", [101])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.citation_chunk_ids == [101, 103]
    assert len(orgs_of(resp.citation_chunk_ids, found)) == 2


async def test_citations_at_the_cap_still_include_two_orgs():
    """모델이 A운용 chunk로 상한(5개)을 다 채워도 결과는 5개 이하이고 B운용 chunk가 들어 있다. 첫 인용은 유지된다"""
    found = [chunk(i, "A운용") for i in range(101, 107)] + [chunk(201, "B운용")]
    claude = FakeClaude([
        tool_resp("search_documents", SEARCH_DISCLOSURE),
        text_resp("ANSWER", [101, 102, 103, 104, 105]),
    ])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert MAX_CITATIONS == 5
    assert len(resp.citation_chunk_ids) == 5 and len(set(resp.citation_chunk_ids)) == 5
    assert resp.citation_chunk_ids[0] == 101
    assert 201 in resp.citation_chunk_ids
    assert len(orgs_of(resp.citation_chunk_ids, found)) == 2


async def test_citations_already_covering_two_orgs_are_left_as_the_model_chose():
    """모델이 이미 두 운용사를 인용했으면 서버는 더하지도 빼지도 않는다"""
    found = [chunk(101, "A운용"), chunk(102, "B운용"), chunk(103, "A운용"), chunk(104, "B운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("ANSWER", [102, 101])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.citation_chunk_ids == [102, 101]


def test_system_prompt_keeps_generic_etf_name_in_plan_context_but_not_as_product_name_rule():
    """계획 설명의 표기('S&P500 추종 ETF')는 남지만 상품명 대신 쓰라는 규칙은 없다 (#168, D-028)"""
    assert "S&P500 추종 ETF" in SYSTEM_PROMPT
    assert "상품명 대신" not in SYSTEM_PROMPT


# ---- AC-1(검색 쪽): 후보에 한 운용사뿐이면 그 운용사를 뺀 재검색을 한 번 더 한다 ----

def row(i: int, org: str, doc_type: str = "DISCLOSURE") -> dict[str, Any]:
    return {
        "chunk_id": i, "content": f"청크 {i}", "title": f"문서 {org}", "source_url": f"https://{org}/doc",
        "doc_type": doc_type, "org_name": org, "distance": i / 10,
    }


class SequencedPool:
    """execute 호출 순서대로 batches[n]을 돌려주는 풀 대역. 호출이 batches보다 많으면 마지막 것을 되풀이한다."""

    def __init__(self, batches: list[list[dict[str, Any]]]):
        self.batches = batches
        self.executed: list[tuple[str, dict[str, Any]]] = []

    def connection(self) -> "SequencedPool":
        return self

    async def __aenter__(self) -> "SequencedPool":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    def cursor(self, **_: Any) -> "SequencedPool":
        return self

    async def execute(self, sql: str, params: dict[str, Any]) -> None:
        self.executed.append((sql, params))

    async def fetchall(self) -> list[dict[str, Any]]:
        return self.batches[min(len(self.executed), len(self.batches)) - 1]


class FakeEmbedder:
    model = "fake-embedding-model"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [[0.5, 0.5] for _ in texts]


def make_retriever(batches: list[list[dict[str, Any]]], top_k: int = 5) -> tuple[Retriever, SequencedPool]:
    pool = SequencedPool(batches)
    return Retriever(cast(AsyncConnectionPool, pool), cast(Embedder, FakeEmbedder()), top_k), pool


async def test_disclosure_search_re_searches_once_when_candidates_have_a_single_org():
    """첫 후보가 A운용뿐이면 한 번 더 검색해 B운용 chunk를 섞는다. 가장 가까운 A운용 chunk는 남고 top_k개다 (FR-AI-03)"""
    first = [row(i, "A운용") for i in range(1, 7)]
    second = [row(7, "B운용"), row(8, "B운용")]
    retriever, pool = make_retriever([first, second])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert len(pool.executed) == 2
    assert len(got) == 5
    assert got[0].chunk_id == 1
    assert 7 in [c.chunk_id for c in got]
    assert {c.org_name for c in got} == {"A운용", "B운용"}
    assert len({c.chunk_id for c in got}) == 5


async def test_re_search_keeps_the_active_index_conditions():
    """재검색 SQL도 활성 임베딩·활성 문서·모델 코드 조건을 지킨다 (rag.md 검색 불변 조건)"""
    retriever, pool = make_retriever([[row(i, "A운용") for i in range(1, 7)], [row(7, "B운용")]])

    await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert len(pool.executed) == 2
    for sql, params in pool.executed:
        assert "e.is_active" in sql and "d.is_active" in sql and "model_code" in sql
        assert params["model"] == "fake-embedding-model"
        assert params["doc_type"] == "DISCLOSURE"


async def test_disclosure_search_does_not_re_search_when_two_orgs_already_present():
    """첫 후보에 이미 운용사가 둘이면 쿼리는 한 번이다"""
    first = [row(1, "A운용"), row(2, "A운용"), row(3, "B운용")]
    retriever, pool = make_retriever([first])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert len(pool.executed) == 1
    assert {c.org_name for c in got} == {"A운용", "B운용"}


@pytest.mark.parametrize("doc_type", [DocType.EDU, None])
async def test_non_disclosure_search_never_re_searches(doc_type):
    """교육자료(EDU)나 doc_type 미지정 검색은 운용사를 따지지 않으므로 한 번만 검색한다"""
    retriever, pool = make_retriever([[row(i, "A운용", "EDU") for i in range(1, 7)], [row(7, "B운용", "EDU")]])

    got = await retriever.search("S&P500 추종 ETF 위험", doc_type, top_k=5)

    assert len(pool.executed) == 1
    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 5]


async def test_top_k_one_keeps_the_closest_chunk_without_re_search():
    """top_k=1이면 운용사 2곳은 불가능하다. 재검색 없이 가장 가까운 chunk 1개를 그대로 돌려준다 (SearchRequest는 top_k=1을 허용한다)"""
    retriever, pool = make_retriever([[row(1, "A운용"), row(2, "A운용")], [row(3, "B운용")]])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=1)

    assert len(pool.executed) == 1
    assert [(c.chunk_id, c.org_name) for c in got] == [(1, "A운용")]


async def test_top_k_one_keeps_the_closest_chunk_even_when_other_org_is_in_candidates():
    """top_k=1이고 후보에 두 운용사가 있어도 최인접 chunk가 다른 운용사 chunk로 바뀌지 않는다"""
    retriever, pool = make_retriever([[row(1, "A운용"), row(2, "B운용")]])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=1)

    assert len(pool.executed) == 1
    assert [c.chunk_id for c in got] == [1]


async def test_top_k_two_re_searches_and_mixes_two_orgs():
    """경계값: top_k=2는 운용사 2곳 규칙이 적용된다. 최인접 A운용 chunk는 유지하고 B운용 chunk가 하나 섞인다"""
    retriever, pool = make_retriever([[row(i, "A운용") for i in range(1, 5)], [row(5, "B운용")]])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=2)

    assert len(pool.executed) == 2
    assert [(c.chunk_id, c.org_name) for c in got] == [(1, "A운용"), (5, "B운용")]


# ---- AC-2: 검색되지 않은 chunk ID는 보정에서 인용에서 빠진다 ----

async def test_unretrieved_chunk_ids_are_removed_from_citations():
    """모델이 낸 chunk ID 중 이번 요청에서 검색되지 않은 것(999)은 응답에 없다. 중복도 하나로 합친다 (절대 원칙 3)"""
    found = [chunk(101, "A운용"), chunk(103, "B운용")]
    claude = FakeClaude([
        tool_resp("search_documents", SEARCH_DISCLOSURE),
        text_resp("ANSWER", [999, 101, 101, 103, 555]),
    ])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.citation_chunk_ids == [101, 103]


async def test_citations_are_empty_when_model_cites_only_unretrieved_ids():
    """검색을 하지 않았는데 모델이 chunk ID를 내놓으면 전부 제거되고 서버가 대신 채우지도 않는다"""
    claude = FakeClaude([text_resp("ANSWER", [101, 103])])
    retriever = FakeRetriever([chunk(101, "A운용"), chunk(103, "B운용")])

    resp = await make_agent(claude, retriever).run(question())

    assert retriever.calls == 0
    assert resp.citation_chunk_ids == []


async def test_previous_request_search_results_do_not_leak_into_the_next_request():
    """같은 에이전트의 앞선 요청에서 검색된 chunk를 다음 요청이 인용하면 제거된다. 인용 근거는 요청마다 새로 쌓인다"""
    found = [chunk(101, "A운용"), chunk(103, "B운용")]
    claude = FakeClaude([
        tool_resp("search_documents", SEARCH_DISCLOSURE),
        text_resp("ANSWER", [101, 103]),
        text_resp("ANSWER", [101, 103]),
    ])
    agent = make_agent(claude, FakeRetriever(found))

    first = await agent.run(question())
    second = await agent.run(question("아까 그 위험 다시 알려줘요"))

    assert first.citation_chunk_ids == [101, 103]
    assert second.citation_chunk_ids == []


async def test_backfill_never_adds_ids_that_were_not_retrieved():
    """서버가 더하는 chunk도 이번 검색 결과 안에서만 고른다"""
    found = [chunk(101, "A운용"), chunk(102, "A운용"), chunk(103, "B운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("ANSWER", [999, 102])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert set(resp.citation_chunk_ids) <= {c.chunk_id for c in found}
    assert resp.citation_chunk_ids == [102, 103]


# ---- AC-3: 출처가 한 곳뿐이면 가능한 만큼만 인용하고 거짓으로 채우지 않는다 ----

async def test_single_org_results_are_cited_as_the_model_chose_without_padding():
    """검색 결과가 A운용 한 곳뿐이면 모델이 인용한 chunk만 남고 서버가 다른 chunk나 ID를 더하지 않는다"""
    found = [chunk(101, "A운용"), chunk(102, "A운용"), chunk(103, "A운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("ANSWER", [101])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.message_type == "ANSWER"
    assert resp.citation_chunk_ids == [101]


async def test_single_org_results_do_not_raise_and_keep_all_valid_citations():
    """운용사 둘을 못 채워도 예외 없이 끝나고, 모델이 낸 유효한 인용(2개)은 그대로다"""
    found = [chunk(101, "A운용"), chunk(102, "A운용"), chunk(103, "A운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("ANSWER", [101, 999, 102])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.citation_chunk_ids == [101, 102]


async def test_single_org_search_returns_only_that_org_after_one_re_search():
    """재검색에서도 다른 운용사가 없으면 A운용 chunk만 돌려준다. 가짜 chunk로 채우지 않고 쿼리는 최대 2번이다"""
    first = [row(i, "A운용") for i in range(1, 7)]
    retriever, pool = make_retriever([first, first])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert len(pool.executed) <= 2
    assert {c.org_name for c in got} == {"A운용"}
    assert [c.chunk_id for c in got] == [1, 2, 3, 4, 5]


async def test_search_with_no_disclosure_candidates_returns_empty():
    """공시 후보가 하나도 없으면 빈 목록이다(재검색이 있어도 예외 없이)"""
    retriever, pool = make_retriever([[]])

    got = await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert got == []
    assert len(pool.executed) <= 2


# ---- 범위 가드: 운용사 보충은 공시 인용에만, 범위 밖 답에는 인용이 없다 ----

async def test_edu_only_citations_are_not_padded_with_disclosure_orgs():
    """교육자료(EDU)만 인용한 답은 운용사 2곳 규칙 대상이 아니다. 모델이 고른 그대로 남는다"""
    found = [
        chunk(201, "금감원", DocType.EDU), chunk(202, "금융위", DocType.EDU), chunk(101, "A운용"), chunk(103, "B운용"),
    ]
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "ANY"}),
        text_resp("ANSWER", [201]),
    ])

    resp = await make_agent(claude, FakeRetriever(found)).run(question("ETF가 뭐예요?"))

    assert resp.citation_chunk_ids == [201]


async def test_out_of_scope_answer_has_no_citations_even_after_disclosure_search():
    """OUT_OF_SCOPE로 끝나면 공시를 검색했어도 인용은 빈 배열이다 (AC-15)"""
    found = [chunk(101, "A운용"), chunk(103, "B운용")]
    claude = FakeClaude([tool_resp("search_documents", SEARCH_DISCLOSURE), text_resp("OUT_OF_SCOPE", [101])])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.citation_chunk_ids == []


# ---- 코드 리뷰 보강(#40) ----

async def test_re_search_excludes_the_orgs_already_found():
    """재검색 SQL에는 첫 검색에서 나온 운용사가 exclude_orgs로 넘어가고, 첫 검색에는 제외가 없다"""
    retriever, pool = make_retriever([[row(i, "A운용") for i in range(1, 7)], [row(7, "B운용")]])

    await retriever.search("S&P500 추종 ETF 위험", DocType.DISCLOSURE, top_k=5)

    assert pool.executed[0][1]["exclude_orgs"] is None
    assert pool.executed[1][1]["exclude_orgs"] == ["A운용"]
    assert "o.name <> ALL(%(exclude_orgs)s::text[])" in " ".join(pool.executed[1][0].split())


async def test_cap_keeps_the_only_disclosure_citation_when_it_is_last():
    """상한 5개에서 유일한 공시 인용이 마지막이면 그 앞의 인용을 빼고 다른 운용사 chunk를 넣는다"""
    found = [chunk(i, "A운용", DocType.EDU) for i in range(301, 305)] + [chunk(101, "A운용"), chunk(201, "B운용")]
    claude = FakeClaude([
        tool_resp("search_documents", SEARCH_DISCLOSURE),
        text_resp("ANSWER", [301, 302, 303, 304, 101]),
    ])

    resp = await make_agent(claude, FakeRetriever(found)).run(question())

    assert resp.citation_chunk_ids == [301, 302, 303, 101, 201]


def test_system_prompt_limits_no_plan_copy_to_plan_questions_and_lets_etf_questions_search():
    """plan_context가 null이어도 ETF 일반 질문은 검색해 답한다. 안내 문구는 내 계획의 계산·설명 요청에만 쓴다"""
    assert "내 계획의 계산 결과나 그 설명을 요청하면" in SYSTEM_PROMPT
    assert "내 계획의 계산 결과나 그 설명을 묻는 경우에만" in SYSTEM_PROMPT
    assert "<plan_context>가 null이어도" in SYSTEM_PROMPT
    assert "search_documents로 찾아 답합니다" in SYSTEM_PROMPT

