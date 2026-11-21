"""Issue #168(CF-07, D-028, CP-06, NFR-USA-02): 본문 검사는 개별 주식 종목 추천만 거부한다.

ETF 추천 표현과 ETF 상품명(KODEX 등)은 거부·치환하지 않고 그대로 낸다. 본문에 '추천'류 낱말이 있을 때만 판정 부품
(StockRecommendationJudge)에 개별 주식 종목 추천인지 묻고, true면 FORBIDDEN_EXPRESSION으로 거부해 1회 재시도한다(#98).

공개 진입점 ChatAgent.run으로 검증한다. 네트워크 없이 FakeClaude/FakeCalc/FakeRetriever와 판정 대역을 쓴다.
실제 판정 모델이 종목명(삼성전자·삼전 등)을 제대로 가려내는지는 FakeJudge로 보장할 수 없으므로 수동 평가 몫이다(D-073).
여기서는 서버가 판정 결과를 따르는지, 판정을 언제 부르는지, 판정 프롬프트·시스템 프롬프트 규칙만 본다.
"""

from types import SimpleNamespace

import pytest
from anthropic.types.beta import BetaTextBlock

from app.llm.agent import AgentError, ChatAgent, LlmStockJudge
from app.llm.prompts import STOCK_JUDGE_PROMPT, SYSTEM_PROMPT
from app.schemas import ChatRequest, ChatResponse
from tests.test_out_of_scope import PLAN_CONTEXT, FakeCalc, FakeClaude, FakeRetriever, text_resp

GENERIC = "S&P500 추종 ETF"
PRODUCT_NAMES = ["KODEX", "TIGER", "KBSTAR", "ARIRANG", "HANARO"]


class FakeJudge:
    """준비한 verdict를 순서대로 돌려주고, 받은 문장을 기록한다. 부르지 않아야 하는 경로는 texts가 비어 있는지로 본다."""

    def __init__(self, *verdicts: bool):
        self.verdicts = list(verdicts)
        self.texts: list[str] = []

    async def recommends_individual_stock(self, text: str) -> bool:
        self.texts.append(text)
        return self.verdicts.pop(0)


def make_agent(claude: FakeClaude, judge: FakeJudge | None = None) -> ChatAgent:
    return ChatAgent(
        claude, FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4,
        stock_judge=judge or FakeJudge(),
    )


def question(text: str = "ETF가 뭐예요") -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG")


def explain_request() -> ChatRequest:
    return ChatRequest(
        memberId=1, messageId=9, mode="EXPLAIN", calcRunId=540, baselineScenario="AVG", planContext=PLAN_CONTEXT
    )


async def run_answer(
    content: str, judge: FakeJudge, request: ChatRequest | None = None, message_type: str = "ANSWER"
) -> tuple[ChatResponse | AgentError, FakeClaude]:
    """모델이 content를 낸 한 턴을 돌린다. 거부하면 AgentError를 값으로 돌려준다.
    금지 표현이면 1회 재시도하므로(#98) 재시도 응답도 같은 content로 준비한다."""
    claude = FakeClaude([text_resp(message_type, content), text_resp(message_type, content)])
    try:
        return await make_agent(claude, judge).run(request or question()), claude
    except AgentError as e:
        return e, claude


# ---- AC-1: ETF 추천 표현과 ETF 상품명은 거부·치환하지 않고 그대로 통과한다 ----

@pytest.mark.parametrize("text", [
    f"{GENERIC}를 추천해요. 지수를 따라가요.",
    f"{GENERIC}를 추천합니다.",
    "이 ETF를 추천드려요. 분산 효과가 있어요.",
    "이 ETF를 추천해 드립니다.",
    "이 ETF를 추천할게요.",
])
async def test_etf_recommendation_in_body_passes_unchanged(text):
    """ETF를 추천하는 본문은 판정이 개별 종목 추천이 아니라고 하면 글자 그대로 나가고 모델은 1번만 불린다"""
    judge = FakeJudge(False)
    outcome, claude = await run_answer(text, judge)
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text
    assert len(claude.calls) == 1


@pytest.mark.parametrize("name", PRODUCT_NAMES)
async def test_product_name_in_body_passes_unchanged(name):
    """특정 ETF 상품명은 'S&P500 추종 ETF'로 바뀌지도 거부되지도 않는다. 추천 낱말이 없으면 판정도 부르지 않는다"""
    text = f"{name} 미국S&P500은 지수를 따라가는 ETF예요. 시장 흐름대로 움직여요."
    judge = FakeJudge()
    outcome, claude = await run_answer(text, judge)
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text
    assert judge.texts == []
    assert len(claude.calls) == 1


@pytest.mark.parametrize("text", [
    "KODEX 200은 코스피를 따라가요.",
    "kodex 200과 Tiger 미국S&P500은 지수를 따라가요.",
    "코덱스 미국S&P500은 지수를 따라가요.",
    "타이거 반도체는 변동이 커요.",
    "SOL 미국반도체는 변동이 커요.",
])
async def test_product_name_without_s_and_p_index_also_passes_unchanged(text):
    """어떤 지수인지 알 수 없는 상품명, 소문자·한글 표기도 거부하지 않고 그대로 낸다"""
    outcome, claude = await run_answer(text, FakeJudge())
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text
    assert len(claude.calls) == 1


async def test_product_name_with_etf_recommendation_passes_unchanged():
    """상품명과 ETF 추천 표현이 함께 있어도 통과한다"""
    text = "KODEX 미국S&P500 ETF를 추천해요."
    outcome, _ = await run_answer(text, FakeJudge(False))
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text


async def test_explain_mode_etf_recommendation_and_product_name_pass():
    """EXPLAIN 요청(message_type이 EXPLANATION으로 보정되는 경로)도 같은 정책이다"""
    text = "이 계획의 ETF 부분은 KODEX 미국S&P500에 넣는 거예요. 이 ETF를 추천해요."
    outcome, _ = await run_answer(text, FakeJudge(False), explain_request())
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.message_type == "EXPLANATION"
    assert outcome.content == text


# ---- AC-2: 개별 주식 종목 추천은 FORBIDDEN_EXPRESSION으로 거부하고 1회 재시도한다 ----

@pytest.mark.parametrize("text", [
    "삼성전자 주식을 추천해요.",
    "삼전을 추천드려요.",
    "엔비디아를 추천합니다.",
    "이 종목을 추천해요.",
])
async def test_individual_stock_recommendation_is_rejected_after_one_retry(text):
    """판정이 개별 종목 추천이라고 하면 재시도해도 같으면 FORBIDDEN_EXPRESSION이고, 모델은 정확히 2번이다"""
    judge = FakeJudge(True, True)
    outcome, claude = await run_answer(text, judge)
    assert isinstance(outcome, AgentError), outcome
    assert outcome.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2
    assert judge.texts == [text, text]


async def test_individual_stock_recommendation_then_clean_retry_returns_retry_answer():
    """첫 답이 종목 추천이어도 재시도 답이 통과하면 재시도 답이 나간다(기존 1회 재시도)"""
    clean = f"{GENERIC}는 지수를 따라가요. 개별 종목은 추천하지 않아요."
    claude = FakeClaude([text_resp("ANSWER", "삼성전자 주식을 추천해요."), text_resp("ANSWER", clean)])
    judge = FakeJudge(True, False)
    resp = await make_agent(claude, judge).run(question())
    assert resp.content == clean
    assert len(claude.calls) == 2
    assert judge.texts == ["삼성전자 주식을 추천해요.", clean]


@pytest.mark.parametrize("message_type", ["ANSWER", "CLARIFY"])
async def test_stock_recommendation_check_applies_to_all_non_out_of_scope_types(message_type):
    """CLARIFY·ANSWER 본문 모두 같은 검사를 받는다"""
    outcome, _ = await run_answer("삼성전자 주식을 추천해요.", FakeJudge(True, True), message_type=message_type)
    assert isinstance(outcome, AgentError), outcome
    assert outcome.code == "FORBIDDEN_EXPRESSION"


# ---- 판정을 부르는 때 ----

async def test_judge_is_not_called_when_body_has_no_recommendation_word():
    """추천·권유·권해 낱말이 없는 본문은 판정 모델을 부르지 않는다(응답마다 호출이 늘지 않는다)"""
    text = f"{GENERIC}는 지수를 따라 오르내려요. 삼성전자 같은 개별 종목과는 달라요."
    judge = FakeJudge()
    outcome, claude = await run_answer(text, judge)
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text
    assert judge.texts == []
    assert len(claude.calls) == 1


@pytest.mark.parametrize("word_text", [
    "이 ETF가 좋아 보여서 추천해요.",
    "이 ETF를 권해요.",
    "이 ETF를 권유합니다.",
])
async def test_judge_is_called_for_any_recommendation_word(word_text):
    """추천·권유·권해가 들어 있으면 활용형과 상관없이 판정을 부른다. 판정이 false면 통과한다"""
    judge = FakeJudge(False)
    outcome, _ = await run_answer(word_text, judge)
    assert isinstance(outcome, ChatResponse), outcome
    assert judge.texts == [word_text]


async def test_negated_recommendation_is_judged_and_passes_when_judge_says_no():
    """부정문도 판정에 넘긴다. 판정이 개별 종목 추천이 아니라고 하면 글자 그대로 통과한다"""
    text = "삼성전자 같은 개별 종목은 추천하지 않아요."
    judge = FakeJudge(False)
    outcome, _ = await run_answer(text, judge)
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.content == text
    assert judge.texts == [text]


async def test_judge_is_not_called_for_out_of_scope_body():
    """OUT_OF_SCOPE 본문은 서버 문구로 바뀌므로 판정하지 않는다"""
    judge = FakeJudge()
    outcome, claude = await run_answer("삼성전자 주식을 추천해요.", judge, message_type="OUT_OF_SCOPE")
    assert isinstance(outcome, ChatResponse), outcome
    assert outcome.message_type == "OUT_OF_SCOPE"
    assert judge.texts == []
    assert len(claude.calls) == 1


# ---- 판정 부품(LlmStockJudge): 형식이 어긋나면 거부 쪽으로 기운다 ----

def verdict_resp(text: str):
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=text)])


@pytest.mark.parametrize(("raw", "expected"), [
    ("false", False),
    (" False\n", False),
    ("true", True),
    ("TRUE", True),
    ("잘 모르겠어요", True),
    ("false, 하지만 종목 추천이에요", True),
    ("", True),
])
async def test_llm_stock_judge_fails_closed_on_anything_but_false(raw, expected):
    """정확히 false일 때만 통과시킨다. true·빈 응답·형식 밖 응답은 개별 종목 추천으로 본다"""
    claude = FakeClaude([verdict_resp(raw)])
    assert await LlmStockJudge(claude, "claude-opus-5-5").recommends_individual_stock("본문") is expected


async def test_llm_stock_judge_sends_body_with_judge_prompt_and_small_output():
    """판정 요청: 판정 규칙은 system, 본문은 user 메시지, 출력은 짧게 제한한다"""
    claude = FakeClaude([verdict_resp("false")])
    await LlmStockJudge(claude, "claude-opus-5-5").recommends_individual_stock("삼전을 추천해요")
    (call,) = claude.calls
    assert call["model"] == "claude-opus-5-5"
    assert call["system"] == STOCK_JUDGE_PROMPT
    assert call["max_tokens"] <= 64
    assert "삼전을 추천해요" in str(call["messages"])
    assert "tools" not in call


def test_judge_prompt_separates_individual_stocks_from_etfs():
    """판정 프롬프트는 개별 주식 종목(종목명·줄임말 포함)만 true, ETF 추천·상품명·부정문은 false로 가르게 한다"""
    assert "개별 주식" in STOCK_JUDGE_PROMPT
    assert "삼성전자" in STOCK_JUDGE_PROMPT and "삼전" in STOCK_JUDGE_PROMPT
    assert "ETF" in STOCK_JUDGE_PROMPT
    assert "true" in STOCK_JUDGE_PROMPT and "false" in STOCK_JUDGE_PROMPT


# ---- AC-3: 시스템 프롬프트 규칙 ----

def test_system_prompt_forbids_only_individual_stock_recommendation():
    """모든 추천 금지가 '개별 주식 종목 추천 금지'로 바뀌었다"""
    assert "개별 주식 종목" in SYSTEM_PROMPT
    assert "추천" in SYSTEM_PROMPT
    assert "특정 금융상품을 추천하거나 고르지 않습니다" not in SYSTEM_PROMPT
    assert '"추천해요", "추천합니다"처럼 특정 상품을 사라고 권하는 표현을 쓰지 않습니다' not in SYSTEM_PROMPT


def test_system_prompt_no_longer_requires_generic_name_instead_of_product_name():
    """상품명 대신 'S&P500 추종 ETF'로만 쓰라는 규칙이 없다(상품명 허용). 'S&P500 추종 ETF'라는 표현 자체는 남아도 된다"""
    assert "상품명 대신" not in SYSTEM_PROMPT
    assert "상품명을 쓰지 않고" not in SYSTEM_PROMPT
    assert "본문에 옮기지 않습니다" not in SYSTEM_PROMPT
    assert GENERIC in SYSTEM_PROMPT


def test_system_prompt_does_not_contain_concrete_product_names():
    """프롬프트 자체에 상품명을 넣지 않는다(모델이 따라 쓰는 것을 막는다)"""
    upper = SYSTEM_PROMPT.upper()
    for name in PRODUCT_NAMES:
        assert name not in upper
