"""Issue #36(T-028): 본문 상품명·추천 표현 검사(M-4, CP-06, NFR-USA-02, D-028, CF-07).

assemble이 어느 모듈에 있든 통과하도록 공개 진입점 ChatAgent.run으로만 검증한다. 네트워크 없이
FakeClaude/FakeCalc/FakeRetriever를 쓴다. 모델이 프롬프트 규칙을 실제로 지키는지는 FakeClaude로 보장할 수 없으므로
수동 평가 몫이다(D-073). 여기서는 모델 출력(본문)이 상품명·추천 표현을 담았을 때 서버가 막는지만 본다.

"막는다"는 둘 중 하나다: (a) AgentError로 거부한다(Spring이 FAILED 처리, FR-AI-04) 또는
(b) 본문을 고쳐 돌려주되 금지 표현이 남지 않는다. 어느 쪽이든 금지 표현이 응답 본문에 그대로 나가면 실패다.
"""

import pytest

from app.llm.agent import AgentError, ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ChatResponse, DocType, RetrievedChunk
from tests.test_out_of_scope import PLAN_CONTEXT, FakeCalc, FakeClaude, FakeRetriever, text_resp, tool_resp

GENERIC = "S&P500 추종 ETF"
PRODUCT_NAMES = ["KODEX", "TIGER", "KBSTAR", "ARIRANG", "HANARO"]


def make_agent(claude: FakeClaude, retriever: FakeRetriever | None = None) -> ChatAgent:
    return ChatAgent(
        claude, FakeCalc(), retriever or FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4
    )


def question(text: str = "ETF가 뭐예요") -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG")


def explain_request() -> ChatRequest:
    return ChatRequest(
        memberId=1, messageId=9, mode="EXPLAIN", calcRunId=540, baselineScenario="AVG", planContext=PLAN_CONTEXT
    )


def product_chunk(chunk_id: int, title: str) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id, content="KODEX 미국S&P500 운용 설명. 지수를 따라가요", title=title,
        source_url=f"https://example.com/{chunk_id}", doc_type=DocType.DISCLOSURE, org_name="삼성자산운용",
        distance=0.1,
    )


async def run_answer(content: str, request: ChatRequest | None = None) -> ChatResponse | AgentError:
    """모델이 content를 낸 한 턴을 돌린다. 거부하면 AgentError를 돌려준다(올리지 않고 값으로 받는다)."""
    try:
        return await make_agent(FakeClaude([text_resp("ANSWER", content)])).run(request or question())
    except AgentError as e:
        return e


def assert_blocked(outcome: ChatResponse | AgentError, forbidden: str) -> None:
    """거부(AgentError)됐거나, 돌려준 본문에 금지 표현이 없다."""
    if isinstance(outcome, AgentError):
        return
    assert forbidden not in outcome.content, outcome.content


# ---- AC-1: 본문의 상품명은 거부되거나 'S&P500 추종 ETF'로 바뀐다 ----

@pytest.mark.parametrize("name", PRODUCT_NAMES)
async def test_product_name_in_body_is_rejected_or_replaced_with_generic_name(name):
    """본문에 상품명이 있으면 거부되거나, 상품명이 사라지고 'S&P500 추종 ETF'가 들어간 본문이 나간다"""
    outcome = await run_answer(f"{name} 미국S&P500은 지수를 따라가는 ETF예요. 시장 흐름대로 움직여요.")
    assert_blocked(outcome, name)
    if isinstance(outcome, ChatResponse):
        assert GENERIC in outcome.content


async def test_product_name_check_ignores_letter_case():
    """소문자·혼합 표기(kodex, Tiger)도 같은 상품명으로 본다"""
    for text in ("kodex 200은 지수를 따라가요", "Tiger 미국S&P500은 지수를 따라가요"):
        outcome = await run_answer(text)
        if isinstance(outcome, ChatResponse):
            lowered = outcome.content.lower()
            assert "kodex" not in lowered and "tiger" not in lowered, outcome.content


async def test_product_name_in_explain_mode_body_is_also_blocked():
    """EXPLAIN 요청의 설명 본문에도 같은 검사가 적용된다 (message_type이 EXPLANATION으로 보정되는 경로)"""
    outcome = await run_answer("이 계획의 ETF 부분은 KODEX 미국S&P500에 넣는 거예요.", explain_request())
    assert_blocked(outcome, "KODEX")


async def test_body_with_only_generic_name_passes_unchanged():
    """상품명이 없는 본문('S&P500 추종 ETF', 'ETF', 'S&P 500')은 글자 그대로 통과한다. 과검사 방지"""
    text = "적금과 S&P500 추종 ETF에 나눠 넣는 계획이에요. ETF는 S&P 500 지수를 따라가요."
    outcome = await run_answer(text)
    assert isinstance(outcome, ChatResponse)
    assert outcome.content == text


# ---- AC-2: 출처 제목에 상품명이 있어도 출처는 그대로 보존된다(D-028) ----

async def test_citation_with_product_name_title_is_preserved_when_body_is_clean():
    """본문에 상품명이 없으면 상품명이 제목인 출처를 인용해도 거부되지 않고 citationChunkIds가 그대로 남는다"""
    chunk = product_chunk(31, "KODEX 미국S&P500 투자설명서")
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "S&P500 ETF 위험", "doc_type": "DISCLOSURE"}),
        text_resp("ANSWER", f"{GENERIC}는 지수가 내리면 같이 내려요.", citations=[31]),
    ])
    resp = await make_agent(claude, FakeRetriever([chunk])).run(question("ETF 위험이 뭐예요"))
    assert resp.citation_chunk_ids == [31]
    assert GENERIC in resp.content
    assert chunk.title == "KODEX 미국S&P500 투자설명서"   # 출처 제목은 손대지 않는다


async def test_product_name_in_citation_title_is_not_fed_to_body_check():
    """검사 대상은 모델 본문뿐이다. 검색된 자료(제목·본문)에 TIGER·KODEX가 있어도 본문이 깨끗하면 통과한다"""
    chunks = [product_chunk(41, "TIGER 미국S&P500 투자설명서"), product_chunk(42, "KODEX 미국S&P500 투자설명서")]
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "S&P500 ETF 위험", "doc_type": "DISCLOSURE"}),
        text_resp("ANSWER", f"{GENERIC}는 원금 손실이 날 수 있어요.", citations=[41, 42]),
    ])
    resp = await make_agent(claude, FakeRetriever(chunks)).run(question("ETF 위험이 뭐예요"))
    assert resp.citation_chunk_ids == [41, 42]
    assert resp.content == f"{GENERIC}는 원금 손실이 날 수 있어요."


async def test_body_product_name_is_blocked_even_when_a_product_titled_citation_exists():
    """출처가 있어도 본문의 상품명은 막는다(출처 면제는 제목에만 해당)"""
    chunk = product_chunk(51, "KODEX 미국S&P500 투자설명서")
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "S&P500 ETF 위험", "doc_type": "DISCLOSURE"}),
        text_resp("ANSWER", "KODEX 미국S&P500은 지수가 내리면 같이 내려요.", citations=[51]),
    ])
    try:
        resp: ChatResponse | AgentError = await make_agent(claude, FakeRetriever([chunk])).run(question("ETF 위험"))
    except AgentError as e:
        resp = e
    assert_blocked(resp, "KODEX")


# ---- AC-3: 추천 표현은 걸러지고 부정문은 통과한다(CF-07) ----

@pytest.mark.parametrize("phrase", [
    "이 상품을 추천해요",
    "이 상품을 추천합니다",
    "이 상품을 추천드려요",
    "이 상품을 추천드립니다",
])
async def test_recommendation_expression_is_blocked(phrase):
    """긍정 추천 표현은 거부되거나, 표현이 남지 않은 본문으로 나간다"""
    outcome = await run_answer(f"{GENERIC}는 지수를 따라가요. {phrase}.")
    if isinstance(outcome, ChatResponse):
        assert phrase not in outcome.content, outcome.content
        assert "추천해요" not in outcome.content and "추천합니다" not in outcome.content
        assert "추천드려요" not in outcome.content and "추천드립니다" not in outcome.content


@pytest.mark.parametrize("phrase", [
    "이 상품을 추천하지 않아요",
    "이 상품을 추천하지 않습니다",
    "이 상품을 추천드리지 않아요",
    "특정 상품을 추천하지 않아요",
])
async def test_negated_recommendation_passes_unchanged(phrase):
    """부정문은 글자 그대로 통과한다. 거부되지도 고쳐지지도 않는다"""
    text = f"{GENERIC}는 지수를 따라가요. {phrase}."
    outcome = await run_answer(text)
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text


async def test_positive_recommendation_next_to_negation_is_still_blocked():
    """부정문이 한 문장 있어도 같은 본문의 긍정 추천은 걸러진다"""
    outcome = await run_answer("다른 상품은 추천하지 않아요. 하지만 이 상품은 추천해요.")
    if isinstance(outcome, ChatResponse):
        assert "이 상품은 추천해요" not in outcome.content, outcome.content


async def test_recommendation_check_applies_to_all_non_out_of_scope_types():
    """CLARIFY·ANSWER 본문 모두 같은 검사를 받는다"""
    for message_type in ("ANSWER", "CLARIFY"):
        claude = FakeClaude([text_resp(message_type, "이 상품을 추천해요")])
        try:
            resp: ChatResponse | AgentError = await make_agent(claude).run(question("뭐가 좋아요"))
        except AgentError as e:
            resp = e
        if isinstance(resp, ChatResponse):
            assert "추천해요" not in resp.content, (message_type, resp.content)


# ---- 프롬프트 규칙 (자동으로 확인할 수 있는 부분만) ----

def test_system_prompt_states_generic_name_and_no_recommendation_rules():
    """프롬프트가 본문 표기('S&P500 추종 ETF')와 상품명·추천 금지를 문장으로 담는다"""
    assert GENERIC in SYSTEM_PROMPT
    assert "상품명" in SYSTEM_PROMPT
    assert "추천" in SYSTEM_PROMPT


def test_system_prompt_does_not_contain_concrete_product_names():
    """프롬프트 자체에 상품명을 넣지 않는다(모델이 따라 쓰는 것을 막는다)"""
    upper = SYSTEM_PROMPT.upper()
    for name in PRODUCT_NAMES:
        assert name not in upper
