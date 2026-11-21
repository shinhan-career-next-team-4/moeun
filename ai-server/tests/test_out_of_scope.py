"""Issue #38(T-030): 질문 3유형 분류(조건 변경 / 설명 / 그 밖)와 범위 밖 안내 문구의 문구표 이전(M-5, D-045, D-056).

네트워크 없이 FakeClaude/FakeCalc/FakeRetriever로 검증한다. 모델이 3유형을 올바르게 가르는지는 FakeClaude로
보장할 수 없으므로(D-073) 수동 평가 몫이다. 여기서는 (1) 모델이 OUT_OF_SCOPE로 답했을 때 서버가 문구를
문구표에서 채우는지, (2) 코드에 문장이 하드코딩돼 있지 않은지, (3) 프롬프트가 3유형 규칙과 전용 문구를
담는지만 검사한다. 문장 전체가 아니라 문구표에서 읽은 값과 비교하므로 문구표 문장이 바뀌어도 깨지지 않는다.
"""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.core.copy import load_copy
from app.llm.agent import ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, DocType, RetrievedChunk

APP_DIR = Path(__file__).resolve().parents[1] / "app"
NULLS = dict.fromkeys(["monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"])
PLAN_CONTEXT = json.loads(   # 계약 C1a 예시 planContext
    (APP_DIR.parents[1] / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json")
    .read_text(encoding="utf-8")
)
CONDITION_KEYS = (
    "goalAmount", "monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths",
)
OUT_OF_SCOPE_KEY = "outOfScope"
UNCHANGEABLE_KEY = "chat.unchangeableCondition"
OUT_OF_SCOPE_OPENING = "이 질문은 모은에서 다루지 않아요"   # 이슈가 글자 그대로 적은 시작 문장
MODEL_OWN_SENTENCE = "삼성전자는 제가 잘 모르는 분야예요"   # 모델이 마음대로 쓴 문장. 응답에 남으면 안 된다


# ---- fake (tests/test_agent_tools.py와 같은 모양. 파일끼리 묶이지 않도록 따로 둔다) ----

def text_resp(message_type: str, content: str = "답", citations: list[int] | None = None) -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": citations or []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(name: str, input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name=name, input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeCalc:
    def __init__(self):
        self.calls: list[dict[str, Any]] = []

    async def recalculate(self, member_id, baseline, changes):
        self.calls.append(changes.changed())
        return {"calcRunId": 77, "goodCase": {"finalAmount": 10_500_000}}


class FakeRetriever:
    def __init__(self, chunks: list[RetrievedChunk] | None = None):
        self.chunks = chunks or []
        self.queries: list[str] = []

    async def search(self, query, doc_type: DocType | None = None, top_k=None) -> list[RetrievedChunk]:
        self.queries.append(query)
        return self.chunks


class MarkerJudge:
    """종목명(삼성전자·삼전)이 든 문장을 개별 주식 종목 추천으로 판정하는 대역. 재시도·숫자 검사 흐름을 보는 테스트가 쓴다.
    실제 판정 품질은 수동 평가 몫이다(D-073). 판정 호출은 모델 호출 수(claude.calls)에 세지 않는다"""

    async def recommends_individual_stock(self, text: str) -> bool:
        return "삼성전자" in text or "삼전" in text


def make_agent(claude, calc, retriever=None):
    return ChatAgent(claude, calc, retriever or FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text: str, **kw) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


def etf_chunk(chunk_id: int) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id, content="ETF는 여러 자산을 묶어 거래소에서 사고파는 펀드예요", title="ETF 이해하기",
        source_url="https://example.com/etf", doc_type=DocType.EDU, org_name="금융감독원", distance=0.1,
    )


def python_sources() -> list[Path]:
    return sorted(p for p in APP_DIR.rglob("*.py") if "__pycache__" not in p.parts)


# ---- AC-1: 개별주식 질문은 계산 없이 OUT_OF_SCOPE, 문구는 문구표에서 ----

async def test_individual_stock_question_is_out_of_scope_without_calc_run():
    """'삼성전자 주식 사면 어때요'는 calcRunId·conditionChanges 없이 OUT_OF_SCOPE다 (FR-AI-09, D-045)"""
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE)])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("삼성전자 주식 사면 어때요", plan_context=PLAN_CONTEXT))
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert calc.calls == []
    wire = resp.model_dump(by_alias=True)
    assert wire["messageType"] == "OUT_OF_SCOPE" and wire["calcRunId"] is None


async def test_out_of_scope_content_comes_from_copy_book_not_from_model():
    """OUT_OF_SCOPE 문구는 문구표 outOfScope 키 문장이고 모델이 쓴 문장은 남지 않는다 (CP-05, M-5)"""
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE)])
    resp = await make_agent(claude, FakeCalc()).run(question("삼성전자 주식 사면 어때요"))
    copy = load_copy()
    assert resp.content.startswith(copy.get(OUT_OF_SCOPE_KEY))
    assert MODEL_OWN_SENTENCE not in resp.content


async def test_out_of_scope_content_lists_the_five_changeable_conditions_from_copy_book():
    """안내에는 대화로 바꿀 수 있는 5개 조건 이름(문구표 condition.* 값)이 모두 들어간다 (D-090)"""
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE)])
    resp = await make_agent(claude, FakeCalc()).run(question("삼성전자 주식 사면 어때요"))
    copy = load_copy()
    for code in CONDITION_KEYS:
        assert copy.get(f"condition.{code}") in resp.content


async def test_refusal_stop_reason_uses_the_same_copy_book_content():
    """모델이 거절(refusal)해도 같은 문구표 문장으로 OUT_OF_SCOPE 안내를 한다 (D-045)"""
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE)])
    via_answer = await make_agent(claude, FakeCalc()).run(question("삼성전자 주식 사면 어때요"))
    refusal = FakeClaude([SimpleNamespace(stop_reason="refusal", content=[])])
    via_refusal = await make_agent(refusal, FakeCalc()).run(question("삼성전자 주식 사면 어때요"))
    assert via_refusal.message_type == "OUT_OF_SCOPE" and via_refusal.calc_run_id is None
    assert via_refusal.content == via_answer.content
    assert via_refusal.content.startswith(load_copy().get(OUT_OF_SCOPE_KEY))


async def test_out_of_scope_after_stray_recalculate_still_reports_no_calc_run():
    """(예외) 모델이 recalculate를 부른 뒤 OUT_OF_SCOPE로 끝내도 calcRunId·conditionChanges는 없다 (서버 사실)"""
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE),
    ])
    resp = await make_agent(claude, FakeCalc()).run(question("삼성전자 주식 사면 어때요"))
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.calc_run_id is None and not resp.condition_changes
    assert resp.content.startswith(load_copy().get(OUT_OF_SCOPE_KEY))


# ---- AC-2: 설명 질문은 계산 없이 ANSWER, 근거 인용 ----

async def test_explanation_question_answers_with_citation_and_without_calc():
    """'이 ETF가 뭐예요'는 계산 없이 ANSWER이고 검색된 chunk를 인용한다. 모델 문장은 그대로다 (FR-AI-09, D-045)"""
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란 무엇인가", "doc_type": "ANY"}),
        text_resp("ANSWER", "ETF는 여러 자산을 묶어 거래소에서 사고파는 펀드예요", citations=[101]),
    ])
    calc, retriever = FakeCalc(), FakeRetriever([etf_chunk(101)])
    resp = await make_agent(claude, calc, retriever).run(question("이 ETF가 뭐예요", plan_context=PLAN_CONTEXT))
    assert retriever.queries == ["ETF란 무엇인가"]
    assert calc.calls == []
    assert resp.message_type == "ANSWER"
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert resp.citation_chunk_ids == [101]
    assert resp.content == "ETF는 여러 자산을 묶어 거래소에서 사고파는 펀드예요"
    assert OUT_OF_SCOPE_OPENING not in resp.content


# ---- 프롬프트: 3유형 분류 규칙 (D-045) ----

def test_system_prompt_defines_three_question_types():
    """프롬프트에 조건 변경 / 설명 / 그 밖 3유형이 이름으로 나온다 (D-045)"""
    for label in ("조건 변경", "설명", "그 밖"):
        assert label in SYSTEM_PROMPT


def test_system_prompt_routes_each_type_to_its_message_type():
    """그 밖 유형은 OUT_OF_SCOPE, 설명 유형은 search_documents, 개별 주식 질문도 범위 밖이라고 적는다 (FR-AI-09)"""
    assert "개별 주식" in SYSTEM_PROMPT
    assert "OUT_OF_SCOPE" in SYSTEM_PROMPT
    assert "search_documents" in SYSTEM_PROMPT
    section = SYSTEM_PROMPT.split("## 범위", 1)[1].split("\n## ", 1)[0]
    assert "그 밖" in section and "OUT_OF_SCOPE" in section and "개별 주식" in section


def test_system_prompt_quotes_unchangeable_condition_copy_from_copy_book():
    """프롬프트의 대화로 못 바꾸는 조건 안내 문구는 문구표 chat.unchangeableCondition 값이다 (D-056)"""
    assert load_copy().get(UNCHANGEABLE_KEY) in SYSTEM_PROMPT


# ---- AC-3: 하드코딩 문구 없음 ----

def test_out_of_scope_copy_constant_is_gone_from_code():
    """app/ 아래 어디에도 OUT_OF_SCOPE_COPY 이름과 그 문장이 없다 (CP-05, M-5)"""
    hits = [str(p.relative_to(APP_DIR)) for p in python_sources() if "OUT_OF_SCOPE_COPY" in p.read_text(encoding="utf-8")]
    assert hits == []


def test_notice_sentences_are_not_hardcoded_in_code():
    """범위 밖·목표 금액 안내 문장은 문구표 값에서만 온다. app/ 코드에 같은 문장이 글자로 박혀 있지 않다 (CP-05, D-056)"""
    copy = load_copy()
    sentences = (
        OUT_OF_SCOPE_OPENING,
        copy.get(OUT_OF_SCOPE_KEY),
        copy.get(UNCHANGEABLE_KEY),
    )
    for path in python_sources():
        source = path.read_text(encoding="utf-8")
        for sentence in sentences:
            assert sentence not in source, f"{path.relative_to(APP_DIR)}에 문구가 하드코딩돼 있어요: {sentence[:20]}"


# ---- AC-4: 목표 금액 변경 발화는 ANSWER + 전용 문구 키 ----

async def test_goal_amount_utterance_is_answer_with_unchangeable_condition_copy():
    """'목표 금액을 1억으로 바꿔줘'는 OUT_OF_SCOPE가 아니라 ANSWER이고 chat.unchangeableCondition 문장이다 (D-056)"""
    expected = load_copy().get(UNCHANGEABLE_KEY)
    claude = FakeClaude([text_resp("ANSWER", expected)])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("목표 금액을 1억으로 바꿔줘", plan_context=PLAN_CONTEXT))
    assert resp.message_type == "ANSWER"
    assert resp.content == expected
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert calc.calls == []
    wire = resp.model_dump(by_alias=True)
    assert wire["messageType"] == "ANSWER" and wire["calcRunId"] is None


async def test_goal_amount_answer_is_not_replaced_by_out_of_scope_copy():
    """OUT_OF_SCOPE 문구 채우기는 ANSWER에 번지지 않는다. 목표 금액 ANSWER 문장은 그대로 나간다 (D-056)"""
    expected = load_copy().get(UNCHANGEABLE_KEY)
    claude = FakeClaude([text_resp("ANSWER", expected)])
    resp = await make_agent(claude, FakeCalc()).run(question("목표 금액을 1억으로 바꿔줘"))
    assert load_copy().get(OUT_OF_SCOPE_KEY) not in resp.content


# ---- AC-5(예외): 두 안내는 서로 다른 키·다른 시작 문장 ----

def test_out_of_scope_and_unchangeable_condition_use_different_keys_and_openings():
    """두 키는 다르고 대화로 못 바꾸는 조건 안내는 '이 질문은 모은에서 다루지 않아요'로 시작하지 않는다 (D-056)"""
    copy = load_copy()
    assert OUT_OF_SCOPE_KEY != UNCHANGEABLE_KEY
    assert copy.get(OUT_OF_SCOPE_KEY) != copy.get(UNCHANGEABLE_KEY)
    assert copy.get(OUT_OF_SCOPE_KEY).startswith(OUT_OF_SCOPE_OPENING)
    assert not copy.get(UNCHANGEABLE_KEY).startswith(OUT_OF_SCOPE_OPENING)


async def test_the_two_notices_end_up_as_different_responses():
    """같은 에이전트에서 범위 밖 질문과 목표 금액 발화의 응답은 타입도 문장도 다르다 (D-056)"""
    copy = load_copy()
    stock = await make_agent(FakeClaude([text_resp("OUT_OF_SCOPE", MODEL_OWN_SENTENCE)]), FakeCalc()).run(
        question("삼성전자 주식 사면 어때요")
    )
    goal = await make_agent(FakeClaude([text_resp("ANSWER", copy.get(UNCHANGEABLE_KEY))]), FakeCalc()).run(
        question("목표 금액을 1억으로 바꿔줘")
    )
    assert (stock.message_type, goal.message_type) == ("OUT_OF_SCOPE", "ANSWER")
    assert stock.content != goal.content
    assert stock.content.startswith(OUT_OF_SCOPE_OPENING)
    assert not goal.content.startswith(OUT_OF_SCOPE_OPENING)
