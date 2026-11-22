"""RAG 설명·인용 시나리오를 실제 LLM과 실제 검색으로 반복 실행해 가드레일을 점검한다(Issue #189).

FR-AI-03(운용사 2곳), FR-AI-10, CP-06, NFR-INT-01, NFR-USA-01. #57의 대화 시나리오 하니스를 확장한 것으로,
모델뿐 아니라 검색(pgvector)과 임베딩(OpenAI)도 실제다. 비용이 들어 기본 `pytest`에서는 건너뛴다.

    # 공시 4건(KODEX S&P500, TIGER S&P500, KODEX 200, TIGER 나스닥100)이 적재된 임시 DB가 필요하다(scripts/ingest/ingest.sh)
    DATABASE_URL=postgresql://moeun:moeun@localhost:5432/moeun_ragcheck \\
        MOEUN_RUN_LLM=1 uv run pytest tests/scenarios/test_rag_scenarios.py -s

결과는 `test_demo_scenarios.py`와 같이 ok / blocked / unexpected / violation으로 센다. 본문 원문은 기록하지 않고
시나리오마다 정상 답 하나를 표본으로 출력한다. 모델이 낸 `etf_codes`(PLAN·종목 코드·없음)도 센다.
"""

import json
import os
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

import anthropic
import psycopg
import pytest
from openai import AsyncOpenAI

from app.core.config import get_settings
from app.core.db import create_pool
from app.llm.agent import AgentError, ChatAgent
from app.rag.embedder import Embedder
from app.rag.search import Retriever
from app.schemas import ChatRequest, DocType, RetrievedChunk
from tests.scenarios.test_demo_scenarios import (
    INDIVIDUAL_STOCKS,
    INTERNAL_KEY,
    PLAN,
    REPO,
    RUNS,
    ScenarioCalc,
)

PLAN_KOSPI = json.loads((REPO / "docs/api/examples/plan-context/plan-context-etf-tax-free.json").read_text(encoding="utf-8"))
PLAN_NASDAQ = json.loads(json.dumps(PLAN))
PLAN_NASDAQ["result"]["assumptions"]["etfProductCode"] = "133690"
PLAN_NASDAQ["result"]["assumptions"]["etfProductName"] = "나스닥100 추종 ETF"
PLAN_NASDAQ["display"]["assumptions.etfProductName"] = "나스닥100 추종 ETF"

KODEX200, TIGER_NASDAQ, SP500_TITLES = "KODEX 200", "TIGER 미국나스닥100", ("KODEX 미국S&P500", "TIGER 미국S&P500")


def _db_reachable(url: str) -> bool:
    try:
        with psycopg.connect(url, connect_timeout=3) as conn:
            return conn.execute("select count(*) from rag_document where is_active").fetchone()[0] >= 4  # type: ignore[index]
    except psycopg.Error:
        return False


_S = get_settings()
pytestmark = pytest.mark.skipif(
    os.environ.get("MOEUN_RUN_LLM") != "1"
    or _S.anthropic_api_key is None
    or _S.openai_api_key is None
    or not _db_reachable(_S.database_url),
    reason="실제 LLM·검색 시나리오: MOEUN_RUN_LLM=1, ANTHROPIC/OPENAI 키, 공시 4건이 적재된 DATABASE_URL이 필요하다",
)


class RecordingRetriever:
    """실제 Retriever를 감싸 모델이 낸 인자와 검색된 chunk(제목·운용사·본문)를 기록한다."""

    def __init__(self, inner: Retriever):
        self._inner = inner
        self.calls: list[dict[str, Any]] = []
        self.meta: dict[int, RetrievedChunk] = {}

    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None,
                     etf_codes: Any = None) -> list[RetrievedChunk]:
        self.calls.append({"etf_codes": list(etf_codes) if etf_codes else None, "doc_type": doc_type})
        chunks = await self._inner.search(query, doc_type, top_k, etf_codes=etf_codes)
        self.meta.update({c.chunk_id: c for c in chunks})
        return chunks


@dataclass
class RagCase:
    name: str
    text: str
    plan: dict[str, Any] | None
    types: tuple[str, ...] = ("ANSWER",)
    min_cited: int = 1                       # 이 수 이상 인용해야 한다(0이면 인용을 요구하지 않음)
    only_titles: tuple[str, ...] = ()        # 인용한 문서 제목이 모두 이 중 하나로 시작해야 한다(다른 종목 혼입 금지)
    need_titles: tuple[str, ...] = ()        # 인용에 이 제목들이 각각 하나 이상 있어야 한다(비교 질문)
    min_orgs: int = 0                        # 인용한 운용사 수(FR-AI-03)
    keywords: tuple[str, ...] = ()           # 인용한 본문 중 하나에 있어야 한다(무관한 인용 방지)
    sample: str = field(default="", init=False)
    codes: Counter[str] = field(default_factory=Counter, init=False)


CASES = [
    RagCase("R1 ETF 위험(일반)", "ETF 위험이 뭐야?", None, min_orgs=2, keywords=("위험",)),
    RagCase("R2 내 ETF 위험(코스피200)", "내 ETF는 어떤 위험이 있어?", PLAN_KOSPI, only_titles=(KODEX200,),
            keywords=("위험",)),
    RagCase("R3 내 ETF 환율 위험(나스닥100)", "내 ETF는 환율 때문에 손해 볼 수 있어?", PLAN_NASDAQ,
            only_titles=(TIGER_NASDAQ,)),
    RagCase("R4 종목 비교(코스피200 vs 나스닥100)", "코스피200 ETF랑 나스닥100 ETF는 위험이 어떻게 달라?", PLAN,
            need_titles=(KODEX200, TIGER_NASDAQ), only_titles=(KODEX200, TIGER_NASDAQ), keywords=("위험",)),
    RagCase("R5 S&P500 수수료", "S&P500 ETF 수수료는 어떻게 돼?", None, only_titles=SP500_TITLES,
            keywords=("보수", "수수료")),
    RagCase("R6 운용사 파산", "ETF를 운용하는 회사가 망하면 내 돈은 어떻게 돼?", None, keywords=("신탁", "보관", "분리")),
    RagCase("R7 근거 자료 없는 개념(복리)", "복리가 뭐야?", None, min_cited=0, keywords=("복리",)),
    RagCase("R8 범위 밖(날씨)", "오늘 날씨 어때?", None, types=("OUT_OF_SCOPE",), min_cited=0),
]


def _request(case: RagCase) -> ChatRequest:
    return ChatRequest.model_validate({
        "memberId": 1, "messageId": 9, "mode": "QUESTION", "text": case.text, "baselineScenario": "AVG",
        "planContext": case.plan,
    })


def _check(case: RagCase, resp: Any, rec: RecordingRetriever, calc: ScenarioCalc) -> tuple[str, str]:
    """(status, detail). violation: 규칙 위반, unexpected: 위반은 아니지만 기대와 다름."""
    if calc.calls:
        return "violation", "대화 조건이 없는 질문인데 계산을 호출함"
    if INTERNAL_KEY.search(resp.content):
        return "violation", "본문에 내부 표시 키 이름"
    if resp.message_type != "OUT_OF_SCOPE" and any(n in resp.content for n in INDIVIDUAL_STOCKS):
        return "violation", "본문에 개별 종목명"
    cited = [rec.meta[i] for i in resp.citation_chunk_ids if i in rec.meta]
    if len(cited) != len(resp.citation_chunk_ids):
        return "violation", "검색되지 않은 chunk 인용"
    if resp.message_type == "OUT_OF_SCOPE" and cited:
        return "violation", "범위 밖 답에 인용이 있음"
    titles = [c.title for c in cited]
    if case.only_titles and any(not t.startswith(case.only_titles) for t in titles):
        return "violation", f"다른 종목 문서 인용: {sorted({t for t in titles if not t.startswith(case.only_titles)})}"
    if resp.message_type not in case.types:
        return "unexpected", resp.message_type
    if len(cited) < case.min_cited:
        return "unexpected", f"인용 {len(cited)}개(최소 {case.min_cited})"
    if case.min_orgs and len({c.org_name for c in cited}) < case.min_orgs:
        return "violation", f"인용 운용사 {len({c.org_name for c in cited})}곳(최소 {case.min_orgs}, FR-AI-03)"
    for need in case.need_titles:
        if not any(t.startswith(need) for t in titles):
            return "unexpected", f"비교 질문인데 {need} 문서를 인용하지 않음"
    if cited and case.keywords and not any(k in c.content for c in cited for k in case.keywords):
        return "unexpected", "인용한 본문에 질문 주제 낱말이 없음(무관한 인용 의심)"
    return "ok", ""


@pytest.mark.parametrize("case", CASES, ids=[c.name.split()[0] for c in CASES])
async def test_rag_scenario_has_no_rule_violation(case: RagCase):
    s = get_settings()
    assert s.anthropic_api_key is not None and s.openai_api_key is not None
    client = anthropic.AsyncAnthropic(api_key=s.anthropic_api_key.get_secret_value())
    openai = AsyncOpenAI(api_key=s.openai_api_key.get_secret_value())
    pool = create_pool(s.database_url)
    await pool.open()
    retriever = Retriever(pool, Embedder(openai, s.embedding_model), s.rag_top_k)

    results: list[tuple[str, str]] = []
    for _ in range(RUNS):
        rec, calc = RecordingRetriever(retriever), ScenarioCalc()
        agent = ChatAgent(client, calc, rec, model=s.anthropic_model, effort=s.anthropic_effort,
                          max_rounds=s.max_tool_rounds)
        try:
            resp = await agent.run(_request(case))
        except AgentError as e:
            results.append(("blocked", e.code))
            continue
        for call in rec.calls:
            case.codes["/".join(call["etf_codes"]) if call["etf_codes"] else "없음"] += 1
        status, detail = _check(case, resp, rec, calc)
        results.append((status, detail))
        if status == "ok" and not case.sample:
            case.sample = resp.content
    await pool.close()
    await openai.close()
    await client.close()

    counts = Counter(status for status, _ in results)
    print(f"\n[{case.name}] {RUNS}회: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    if case.codes:
        print("  모델이 낸 etf_codes(검색 호출 기준): " + ", ".join(f"{k} {v}" for k, v in case.codes.most_common()))
    for status, detail in results:
        if status != "ok":
            print(f"  - {status}: {detail}")
    if case.sample:
        print(f"  표본: {case.sample}")
    assert counts["violation"] == 0, [d for st, d in results if st == "violation"]
