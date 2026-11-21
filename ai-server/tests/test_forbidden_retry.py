"""Issue #98: 금지 표현(M-4) 거부 전 1회 재시도와 거부 로그 (FR-AI-04, CP-06).

공개 진입점 ChatAgent.run으로만 검증한다. 네트워크 없이 FakeClaude/FakeCalc/FakeRetriever를 쓴다.
FakeClaude는 준비한 응답을 순서대로 하나씩 내므로, 모델 호출 수는 claude.calls 길이로 센다.
로그 형식(`reason=<사유>`, `attempt=<1|2>`)은 spec.md 구현 재량 I-2를 따른다.
재시도 응답이 tool_use이면 도구를 실행하지 않고 FORBIDDEN_EXPRESSION으로 거부한다(U-1 결정 A).
재시도 호출에는 tool_choice none을 줘 보통은 tool_use가 나오지 않게 한다(U-1 결정).
재시도 응답도 첫 응답과 같은 assemble 보정(운용사 보충 포함)을 받고, 폐기된 첫 답의 인용은 합치지 않는다(B1 결정 A).
422 뒤 재시도 답이 정상 형식의 OUT_OF_SCOPE면 기존 OUT_OF_SCOPE 보정을 유지한다(B1 2차 결정 A).
기획 검토 B2~B8의 테스트 요구사항은 해당 테스트 docstring에 B 번호로 적었다.
"""

import asyncio
import copy
import json
import logging
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import anthropic
import httpx
import httpx2  # anthropic SDK가 쓰는 HTTP 클라이언트(httpx와 다른 패키지)
import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock
from fastapi.testclient import TestClient

from app.api.deps import get_agent
from app.core.config import get_settings
from app.core.copy import load_copy
from app.llm.agent import AgentError, ChatAgent, out_of_scope_content
from app.main import app
from app.schemas import ChatRequest, ChatResponse, DocType, RetrievedChunk
from app.tools.calc import CalcRejected
from tests.fakes import FakeCalc as FixtureCalc
from tests.test_out_of_scope import (
    NULLS,
    PLAN_CONTEXT,
    FakeCalc,
    FakeClaude,
    FakeRetriever,
    MarkerJudge,
    text_resp,
    tool_resp,
)

GENERIC = "S&P500 추종 ETF"
CALC_EXAMPLES = Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "internal-calc"
CLEAN = f"{GENERIC}는 지수를 따라 오르내려요."
RECOMMEND = f"{GENERIC}는 지수를 따라가요. 삼성전자 주식을 추천해요."
OTHER_STOCK = "삼전을 추천드려요."   # 사유는 같고 문장만 다른 두 번째 거부 본문
# 로그에 본문 전문이 남는지 보는 표지. 한글은 JSON 이스케이프(\uXXXX)로 남으면 원문 검사에 안 걸리므로
# ASCII 표지를 함께 넣고, leaked()가 원문·이스케이프 형태를 모두 본다(기획 검토 2차 B4)
BODY_MARK, RETRY_MARK = "BODYMARKQZ", "RETRYMARKQZ"
BODY_SENTINEL = f"비밀문장가나다라 {BODY_MARK}"
RETRY_SENTINEL = f"재시도표지마바사 {RETRY_MARK}"   # 재시도 응답 본문이 로그에 남는지 보는 표지
# 교정 지시가 걸린 표현의 제거·대체를 요청하는지 보는 말(B3, I-1). 사유 낱말과 같은 절에 하나 이상 있어야 한다
REMOVE_OR_REPLACE = (
    "빼", "지우", "지워", "제거", "삭제", "없애", "없이", "바꾸", "바꿔", "대신", "대체",
    "쓰지 말", "쓰지 마", "쓰지 않",
)
# 걸린 표현을 남기거나 쓰라는 말. 그 목적어가 걸린 표현이면 교정 지시가 아니다(기획 검토 3차 B1, 이번 실행 B1)
KEEP_WORDS = (
    "그대로", "남겨", "남기", "유지", "놔두", "놓아두", "살려", "살리", "사용하",
)
_KEEP = re.compile("|".join(KEEP_WORDS) + r"|쓰(?:세요|십시오|고|며|면|도|$)|써(?:\s|도|서|야|$)")
_REMOVE = re.compile("|".join(re.escape(w) for w in REMOVE_OR_REPLACE))
# 남기지·쓰지 말라는 말은 제거 요청이다("추천 표현을 그대로 쓰지 마세요"). 남김 낱말 검사 전에 제거 낱말로 바꾼다
_NEGATED_KEEP = re.compile(
    r"(?:남겨\s*두|남기|남겨|유지하|놔두|그대로\s*(?:두|쓰|사용하)|사용하|쓰)\s*지\s*(?:말|마|않)"
)
# 제거·대체를 하지 말라는 말과 원문 재출력 지시. 제거 낱말을 담아도 교정 지시가 아니다(기획 검토 2차 B3)
_NEGATED_REMOVAL = re.compile(r"(?:빼|지우|지워|제거하|삭제하|없애|바꾸|바꿔|대체하|대신하)\s*지\s*(?:말|마|않)")
_REPRINT = re.compile(r"다시\s*출력|(?:원문|같은 문장|그 문장)\S*\s*(?:\S+\s*)?다시")
# 절 경계: 문장부호와 연결 어미(-고·-며·-면서·-지만) 뒤 공백
_CLAUSE = re.compile(r"[.!?,\n]|(?<=[가-힣])(?:고|며|면서|지만)\s")
# 목적어 조사(을·를·은·는·만·도)와 목적어가 아님을 보이는 그 밖의 조사
_OBJECT_PARTICLE = re.compile(r"(?:을|를|은|는|만|도)$")
_OTHER_PARTICLE = re.compile(r"(?:이|가|로|에|의|와|과|서)$")
# 사유 낱말 바로 뒤에 오면 걸린 표현을 가리키는 말("추천 표현", "상품명이라는 말")
TARGET_NOUNS = ("표현", "문장", "문구", "말", "낱말", "단어")


def _object_before(text: str) -> tuple[str, str]:
    """동사 앞 text에서 목적어와 그 앞 낱말을 찾는다. 바로 앞이 조사 없는 명사면 그것("표현 없이", "표현 대신"),
    아니면 뒤에서부터 목적어 조사가 붙은 첫 낱말이다("상품명을 'S&P500 추종 ETF'로 바꿔" → 상품명)."""
    tokens = [t.strip("'\"\u2018\u2019\u201c\u201d") for t in text.split()]
    if tokens and not (_OBJECT_PARTICLE.search(tokens[-1]) or _OTHER_PARTICLE.search(tokens[-1])):
        return tokens[-1], tokens[-2] if len(tokens) > 1 else ""
    for n in range(len(tokens) - 1, -1, -1):
        if _OBJECT_PARTICLE.search(tokens[n]):
            return _OBJECT_PARTICLE.sub("", tokens[n]), tokens[n - 1] if n else ""
    return "", ""


def _is_target(text: str, keyword: str) -> bool:
    """동사 앞 text의 목적어가 걸린 표현인가: 목적어에 사유 낱말이 있거나, 사유 낱말 바로 뒤의 '표현·문장·말' 등이다.
    "추천 표현이 있는 문단의 인사말만 삭제"의 목적어는 인사말이라 걸린 표현이 아니다(이번 실행 B1)."""
    obj, prev = _object_before(text)
    return keyword in obj or (obj in TARGET_NOUNS and keyword in prev)


def asks_removal(correction: str, keyword: str) -> bool:
    """교정 지시가 사유(keyword)의 표현을 빼거나 바꾸라고 긍정으로 요청하는지 본다. 문장 전체는 고정하지 않는다(I-1).
    사유 낱말이 든 절 중 하나 이상에 부정되지 않은 제거·대체 동사가 있고 그 목적어가 걸린 표현이어야 하며,
    사유 낱말이 든 어느 절에서도 걸린 표현을 남기거나 쓰라고 하지 않아야 하고, 원문을 다시 출력하라는 말은 없어야 한다.
    "추천 표현은 남겨 두고 인사말만 삭제하세요", "추천 표현은 사용하세요. 추천 표현이 있는 문단의 인사말만
    삭제하세요"처럼 다른 문장만 지우라는 지시는 통과하지 않는다(기획 검토 3차 B1, 이번 실행 B1)."""
    if not correction.strip() or keyword not in correction or _REPRINT.search(correction):
        return False
    targeted = False
    for clause in _CLAUSE.split(correction):
        if keyword not in clause:
            continue
        rest = _NEGATED_REMOVAL.sub("", _NEGATED_KEEP.sub(" 제거 ", clause))
        if any(_is_target(rest[: m.start()], keyword) for m in _KEEP.finditer(rest)):
            return False
        targeted = targeted or any(_is_target(rest[: m.start()], keyword) for m in _REMOVE.finditer(rest))
    return targeted


def log_fields(message: str) -> tuple[str | None, str | None]:
    """로그 문장에서 reason·attempt 값을 값 경계까지 잘라 낸다. 문장 전체는 고정하지 않는다(I-2).
    reason=recommendation_unknown, attempt=10처럼 기대값으로 시작하는 다른 값은 다른 값이다(기획 검토 3차 B6)."""
    def value(key: str) -> str | None:
        found = re.search(rf"(?<![\w]){key}=['\"]?([^\s'\",;)\]]+)", message)
        return found.group(1) if found else None
    return value("reason"), value("attempt")


def leaked(text: str, *sentinels: str) -> list[str]:
    """text에 남은 표지. 원문, 한글 부분과 ASCII 표지 각각, 그 JSON 이스케이프 형태(ensure_ascii)를 모두 본다."""
    parts = {p for s in sentinels for p in (s, *s.split())}
    forms = {f for p in parts for f in (p, json.dumps(p)[1:-1])}
    return sorted(f for f in forms if f in text)


class RecordingClaude(FakeClaude):
    """호출마다 그 시점의 messages를 중첩된 블록·도구 결과까지 깊게 복사해 둔다. agent가 같은 리스트를 이어 붙이거나
    기존 메시지 안의 도구 결과를 고쳐도 호출별 입력을 비교할 수 있다."""

    def __init__(self, responses: list[Any]):
        super().__init__(responses)
        self.snapshots: list[list[Any]] = []
        self.tool_snapshots: list[Any] = []   # 호출 시점의 도구 정의(B4)

    async def _create(self, **kwargs: Any) -> Any:
        self.snapshots.append(copy.deepcopy(kwargs["messages"]))
        self.tool_snapshots.append(copy.deepcopy(kwargs.get("tools")))
        return await super()._create(**kwargs)


class ScriptedClaude(FakeClaude):
    """준비 목록에 예외를 넣으면 그 차례의 호출에서 던진다(모델 호출 실패 대역)."""

    async def _create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def message_text(message: Any) -> str:
    content = message["content"]
    if isinstance(content, str):
        return content
    return "".join(b.get("text", "") if isinstance(b, dict) else getattr(b, "text", "") for b in content)


class GatedClaude:
    """요청 text별로 응답 목록을 따로 낸다. gated 요청의 두 번째 호출(재시도)은 release가 set될 때까지 기다린다.
    같은 agent에서 두 요청이 겹칠 때 재시도 기회와 응답이 요청마다 따로인지 본다."""

    def __init__(self, routes: dict[str, list[Any]], gated: str):
        self.routes = {k: list(v) for k, v in routes.items()}
        self.calls_by: dict[str, int] = dict.fromkeys(routes, 0)
        self.choices: list[tuple[str, Any]] = []   # 호출 순서대로 (요청, tool_choice). 시점별 옵션을 본다(2차 B8)
        self.gated = gated
        self.retry_started = asyncio.Event()
        self.release = asyncio.Event()
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        first = message_text(kwargs["messages"][0])
        key = next(k for k in self.routes if k in first)
        self.calls_by[key] += 1
        self.choices.append((key, kwargs.get("tool_choice")))
        if key == self.gated and self.calls_by[key] == 2:
            self.retry_started.set()
            await self.release.wait()
        return self.routes[key].pop(0)


def raw_text_resp(text: str) -> SimpleNamespace:
    """스키마에 맞지 않는 최종 응답(형식 오류) 대역"""
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=text)])


def make_agent(
    claude: Any, calc: Any = None, retriever: FakeRetriever | None = None, max_rounds: int = 4
) -> ChatAgent:
    return ChatAgent(
        claude, calc or FakeCalc(), retriever or FakeRetriever(), model="claude-opus-5-5", effort="medium",
        max_rounds=max_rounds, stock_judge=MarkerJudge(),
    )


def question(text: str = "ETF가 뭐예요", **kw: Any) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


def explain_request() -> ChatRequest:
    return ChatRequest(
        memberId=1, messageId=9, mode="EXPLAIN", calcRunId=540, baselineScenario="AVG", planContext=PLAN_CONTEXT
    )


def etf_chunk(chunk_id: int) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id, content="ETF는 여러 자산을 묶어 거래소에서 사고파는 펀드예요", title="ETF 이해하기",
        source_url=f"https://example.com/{chunk_id}", doc_type=DocType.EDU, org_name="금융투자협회", distance=0.1,
    )


def disclosure_chunk(chunk_id: int, org: str) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id, content="S&P500 추종 ETF는 지수가 내리면 같이 내려요", title=f"{org} 투자설명서",
        source_url=f"https://example.com/{chunk_id}", doc_type=DocType.DISCLOSURE, org_name=org, distance=0.1,
    )


def post_chat(agent: ChatAgent, req: ChatRequest) -> Any:
    """Spring이 보는 경계(/chat HTTP 응답)로 돌린다"""
    app.dependency_overrides[get_agent] = lambda: agent
    try:
        with TestClient(app) as client:
            return client.post(
                "/chat",
                headers={"X-Internal-Api-Key": get_settings().internal_api_key.get_secret_value()},
                json=req.model_dump(mode="json", by_alias=True),
            )
    finally:
        app.dependency_overrides.clear()


async def outcome(agent: ChatAgent, req: ChatRequest) -> ChatResponse | AgentError:
    try:
        return await agent.run(req)
    except AgentError as e:
        return e


def reason_records(caplog: pytest.LogCaptureFixture) -> list[logging.LogRecord]:
    return [r for r in caplog.records if "reason=" in r.getMessage()]


# ---- AC-1: 첫 응답이 금지 표현이고 재시도 응답이 깨끗하면 재시도 응답이 나간다 ----

async def test_ac1_recommendation_then_clean_retry_returns_retry_answer() -> None:
    """첫 응답에 긍정 추천 표현, 재시도 응답은 깨끗 → 재시도 응답이 ANSWER로 나가고 모델은 2번 불린다"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", CLEAN)])
    resp = await outcome(make_agent(claude), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "ANSWER"
    assert resp.content == CLEAN
    assert len(claude.calls) == 2


async def test_ac1_explain_mode_retry_answer_is_corrected_to_explanation() -> None:
    """EXPLAIN 요청도 재시도한다. 재시도 응답은 첫 응답과 같은 보정을 받아 EXPLANATION·calcRunId 540이 된다"""
    clean = f"이 계획은 적금과 {GENERIC}에 나눠 넣어요."
    claude = FakeClaude([text_resp("EXPLANATION", OTHER_STOCK), text_resp("ANSWER", clean)])
    resp = await outcome(make_agent(claude), explain_request())
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "EXPLANATION"
    assert resp.calc_run_id == 540
    assert resp.content == clean


@pytest.mark.parametrize("model_wrote_note", [False, True], ids=["note_appended", "note_not_duplicated"])
async def test_ac1_explain_retry_answer_quoting_tax_rate_gets_tax_note_once(model_wrote_note: bool) -> None:
    """기획 검토 3차 B4: EXPLAIN 요청에서 첫 답이 금지 표현으로 걸리고 재시도 답이 planContext의 세율 표시값(15.4%)을
    인용하면, 첫 응답과 같은 보정대로 문구표 tax.note가 본문 끝에 한 번 붙는다. 재시도 답에 이미 단서가 있으면
    다시 붙이지 않는다. 모델 2번"""
    note = load_copy().get("tax.note")
    answer = "세금은 15.4%로 가정했어요."
    retry_body = f"{answer} {note}" if model_wrote_note else answer
    claude = FakeClaude([text_resp("EXPLANATION", RECOMMEND), text_resp("EXPLANATION", retry_body)])
    resp = await outcome(make_agent(claude), explain_request())
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "EXPLANATION"
    assert resp.calc_run_id == 540
    assert resp.content.startswith(answer) and resp.content.endswith(note), resp.content
    assert resp.content.count(note) == 1
    assert resp.content == (retry_body if model_wrote_note else f"{answer}\n\n{note}")
    assert len(claude.calls) == 2


async def test_ac1_retry_happens_even_when_final_answer_came_in_last_round() -> None:
    """max_rounds를 다 쓴 라운드에서 금지 표현이 나와도 재시도 1회는 한다(TOO_MANY_ROUNDS가 아니다)"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", CLEAN)])
    resp = await outcome(make_agent(claude, max_rounds=1), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.content == CLEAN
    assert len(claude.calls) == 2


async def test_ac7_each_request_gets_its_own_retry() -> None:
    """같은 agent로 두 요청을 연달아 처리해도 재시도 기회는 요청마다 1회다(앞 요청의 재시도가 뒤 요청을 막지 않는다)"""
    claude = FakeClaude([
        text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", CLEAN),
        text_resp("ANSWER", OTHER_STOCK), text_resp("ANSWER", CLEAN),
    ])
    agent = make_agent(claude)
    first = await outcome(agent, question())
    second = await outcome(agent, question("ETF 위험은요?"))
    assert isinstance(first, ChatResponse), first
    assert isinstance(second, ChatResponse), second
    assert len(claude.calls) == 4


async def test_ac7_request_after_a_rejected_retry_still_gets_its_own_retry() -> None:
    """같은 agent에서 요청 A가 재시도 뒤에도 거부돼도, 이어진 요청 B는 자기 재시도 1회를 받아 깨끗한 답을 낸다
    (거부 경로에서도 재시도 상태가 다음 요청에 남지 않는다)"""
    clean_b = f"{GENERIC}는 값이 오르내릴 수 있어요."
    claude = FakeClaude([
        text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", RECOMMEND),
        text_resp("ANSWER", OTHER_STOCK), text_resp("ANSWER", clean_b),
    ])
    agent = make_agent(claude)
    first = await outcome(agent, question())
    assert isinstance(first, AgentError), first
    assert first.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2
    second = await outcome(agent, question("ETF 위험은요?"))
    assert isinstance(second, ChatResponse), second
    assert second.content == clean_b
    assert len(claude.calls) == 4


async def test_ac1_retry_success_reaches_spring_as_normal_200_response() -> None:
    """Spring이 보는 /chat 응답: 재시도로 살아난 턴은 200과 재시도 본문(camelCase)으로 나간다"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", CLEAN)])
    resp = post_chat(make_agent(claude), question())
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["messageType"] == "ANSWER"
    assert body["content"] == CLEAN
    assert len(claude.calls) == 2


async def test_ac7_overlapping_requests_on_same_agent_each_get_own_retry() -> None:
    """같은 agent에서 요청 A의 재시도가 진행 중일 때 요청 B가 들어와도 B는 자기 재시도 1회를 하고,
    두 요청은 각자 자기 재시도 응답을 돌려준다(재시도 상태를 agent 인스턴스에 두지 않는다)"""
    qa, qb = "ETF가 뭐예요", "ETF 위험은요?"
    clean_a, clean_b = f"{GENERIC}는 지수를 따라가요.", f"{GENERIC}는 값이 오르내릴 수 있어요."
    claude = GatedClaude(
        {
            qa: [text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", clean_a)],
            qb: [text_resp("ANSWER", OTHER_STOCK), text_resp("ANSWER", clean_b)],
        },
        gated=qa,
    )
    agent = make_agent(claude)
    task_a = asyncio.create_task(outcome(agent, question(qa)))
    waiter = asyncio.create_task(claude.retry_started.wait())
    await asyncio.wait({task_a, waiter}, timeout=1, return_when=asyncio.FIRST_COMPLETED)
    waiter.cancel()
    assert claude.retry_started.is_set(), f"요청 A가 재시도하지 않았다: {task_a.result() if task_a.done() else None}"
    b = await asyncio.wait_for(outcome(agent, question(qb)), timeout=1)
    claude.release.set()
    a = await asyncio.wait_for(task_a, timeout=1)
    assert isinstance(a, ChatResponse), a
    assert isinstance(b, ChatResponse), b
    assert a.content == clean_a
    assert b.content == clean_b
    assert claude.calls_by == {qa: 2, qb: 2}


async def test_ac7_overlapping_retry_tool_choice_applies_only_to_that_requests_retry() -> None:
    """기획 검토 2차 B8: 요청 A의 재시도 호출(tool_choice none)을 보류한 동안 같은 agent에 요청 B가 들어온다.
    B의 일반 호출(검색이 필요한 첫 호출, 검색 뒤 답 호출)은 tool_choice가 없거나 auto라서 검색을 실제로 하고,
    B의 재시도와 A의 재시도만 none이다(진행 중인 재시도 설정을 인스턴스에서 공유하지 않는다)"""
    qa, qb = "ETF가 뭐예요", "ETF 위험은요?"
    clean_a, clean_b = f"{GENERIC}는 지수를 따라가요.", f"{GENERIC}는 값이 오르내릴 수 있어요."
    retriever = FakeRetriever([etf_chunk(11)])
    claude = GatedClaude(
        {
            qa: [text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", clean_a)],
            qb: [
                tool_resp("search_documents", {"query": "ETF 위험", "doc_type": "EDU"}),
                text_resp("ANSWER", OTHER_STOCK, citations=[11]),
                text_resp("ANSWER", clean_b, citations=[11]),
            ],
        },
        gated=qa,
    )
    agent = make_agent(claude, retriever=retriever)
    task_a = asyncio.create_task(outcome(agent, question(qa)))
    waiter = asyncio.create_task(claude.retry_started.wait())
    await asyncio.wait({task_a, waiter}, timeout=1, return_when=asyncio.FIRST_COMPLETED)
    waiter.cancel()
    assert claude.retry_started.is_set(), f"요청 A가 재시도하지 않았다: {task_a.result() if task_a.done() else None}"
    b = await asyncio.wait_for(outcome(agent, question(qb)), timeout=1)
    claude.release.set()
    a = await asyncio.wait_for(task_a, timeout=1)
    assert isinstance(a, ChatResponse), a
    assert isinstance(b, ChatResponse), b
    assert a.content == clean_a
    assert b.content == clean_b
    assert b.citation_chunk_ids == [11]
    assert retriever.queries == ["ETF 위험"]
    none, normal = {"type": "none"}, (None, {"type": "auto"})
    by_request = {k: [c for key, c in claude.choices if key == k] for k in (qa, qb)}
    assert len(by_request[qa]) == 2 and len(by_request[qb]) == 3, claude.choices
    assert by_request[qa][0] in normal and by_request[qa][1] == none, claude.choices
    assert by_request[qb][0] in normal and by_request[qb][1] in normal, claude.choices
    assert by_request[qb][2] == none, claude.choices


# ---- AC-2: 재시도 응답에도 금지 표현이 있으면 FORBIDDEN_EXPRESSION, 재시도는 최대 1회 ----

async def test_ac2_forbidden_again_on_retry_is_rejected() -> None:
    """재시도 응답에도 추천 표현 → AgentError(FORBIDDEN_EXPRESSION), 모델 호출은 정확히 2번"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", RECOMMEND)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2


async def test_ac2_retry_is_at_most_once() -> None:
    """금지 표현 응답이 계속 준비돼 있어도 모델은 2번만 불린다(남은 응답은 쓰지 않는다)"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND)] * 3 + [text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2
    assert len(claude.responses) == 2


async def test_ac2_retry_with_other_stock_text_is_rejected() -> None:
    """첫 응답과 재시도 응답의 문장이 달라도 재시도 응답이 개별 종목 추천이면 거부한다"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", OTHER_STOCK)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2


@pytest.mark.parametrize("retry_body", [RECOMMEND, OTHER_STOCK], ids=["same_text", "other_text"])
async def test_ac2_stock_recommendation_first_then_forbidden_retry_stops_after_two_calls(retry_body: str) -> None:
    """첫 응답이 개별 종목 추천으로 걸리고 재시도 응답도 금지 표현이면(문장이 바뀌어도) 두 번째 호출에서 끝난다.
    깨끗한 세 번째 응답이 준비돼 있어도 쓰지 않는다(재시도 횟수를 다시 세지 않는다)"""
    claude = FakeClaude([text_resp("ANSWER", OTHER_STOCK), text_resp("ANSWER", retry_body), text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2
    assert len(claude.responses) == 1


async def test_ac2_final_rejection_reaches_spring_as_502_forbidden_expression() -> None:
    """Spring이 보는 /chat 응답: 재시도 뒤에도 금지 표현이면 지금처럼 502 {"error": "FORBIDDEN_EXPRESSION"}이고
    본문은 내보내지 않는다(Spring이 FAILED로 기록하고 실패 안내를 띄운다, FR-AI-04). 모델은 2번 불린다"""
    claude = FakeClaude([
        text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"),
        text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"),
    ])
    resp = post_chat(make_agent(claude), question())
    assert resp.status_code == 502, resp.text
    assert resp.json() == {"error": "FORBIDDEN_EXPRESSION"}
    assert not leaked(resp.text, BODY_SENTINEL), leaked(resp.text, BODY_SENTINEL)
    assert len(claude.calls) == 2


# ---- AC-3: 재시도는 모델 호출만 다시 한다. 도구는 다시 실행하지 않는다 ----

async def test_ac3_recalculate_is_not_repeated_and_recalc_result_survives_retry() -> None:
    """계산 뒤 금지 표현 → 재시도. 계산은 1번뿐이고, 응답은 그 계산의 RECALC_RESULT(calcRunId 77)이며
    본문은 재시도 모델의 본문 그대로다(B2)"""
    calc = FakeCalc()
    retry_body = "월 저축액을 바꾼 결과예요. 목표까지 남은 금액이 줄어요."
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요. 삼성전자 주식을 추천해요."),
        text_resp("ANSWER", retry_body),
    ])
    resp = await outcome(make_agent(claude, calc), question("월 50만원으로 바꾸면?"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.content == retry_body
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 77
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3


async def test_ac3_search_is_not_repeated_and_retry_can_cite_retrieved_chunk() -> None:
    """검색 뒤 금지 표현 → 재시도. 검색은 1번뿐이고, 재시도 응답은 앞서 검색된 chunk를 인용할 수 있다"""
    retriever = FakeRetriever([etf_chunk(11)])
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", RECOMMEND, citations=[11]),
        text_resp("ANSWER", CLEAN, citations=[11]),
    ])
    resp = await outcome(make_agent(claude, retriever=retriever), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.citation_chunk_ids == [11]
    assert retriever.queries == ["ETF란"]
    assert len(claude.calls) == 3


async def test_ac3_retry_call_keeps_previous_messages_and_appends_correction() -> None:
    """재시도 호출의 messages는 금지 표현을 낸 호출의 messages(도구 결과 포함)를 그대로 앞에 두고,
    뒤에 교정 지시를 붙여 user 메시지로 끝난다"""
    claude = RecordingClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"),
        text_resp("ANSWER", CLEAN),
    ])
    await outcome(make_agent(claude, retriever=FakeRetriever([etf_chunk(11)])), question())
    assert len(claude.snapshots) == 3
    before, retry = claude.snapshots[1], claude.snapshots[2]
    assert retry[: len(before)] == before
    assert len(retry) > len(before)
    assert retry[-1]["role"] == "user"
    # D-1(S4 B1): 거부된 첫 답(assistant 턴)은 싣지 않는다. 교정 지시 user 턴 하나만 붙는다
    assert len(retry) == len(before) + 1, retry[len(before):]
    assert all(m["role"] == "user" for m in retry[len(before):])
    assert not leaked(str(retry), BODY_SENTINEL), leaked(str(retry), BODY_SENTINEL)
    # 첫 호출 당시의 검색 결과(도구 결과 블록)가 내용까지 그대로 재시도 호출에 들어간다
    tool_results = [b for m in before if isinstance(m["content"], list) for b in m["content"]
                    if isinstance(b, dict) and b.get("type") == "tool_result"]
    assert len(tool_results) == 1, tool_results
    assert "ETF는 여러 자산을 묶어" in str(tool_results[0]["content"])


async def test_ac3_tool_use_in_retry_response_is_not_executed() -> None:
    """재시도 응답이 도구를 부르려 해도 도구는 다시 실행하지 않고 FORBIDDEN_EXPRESSION으로 거부한다(U-1 결정 A)"""
    calc = FakeCalc()
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요. 삼성전자 주식을 추천해요."),
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 600_000}, id_="tu_2"),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요."),
    ])
    result = await outcome(make_agent(claude, calc), question("월 50만원으로 바꾸면?"))
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3   # 재시도 1번은 했고, 그 뒤로 모델을 더 부르지 않는다


async def test_ac3_search_tool_use_in_retry_response_is_not_executed() -> None:
    """재시도 응답이 새 검색어로 search_documents를 부르려 해도 검색은 다시 하지 않고 FORBIDDEN_EXPRESSION으로
    거부한다(U-1 결정 A). 깨끗한 네 번째 응답이 준비돼 있어도 모델은 3번에서 멈춘다"""
    retriever = FakeRetriever([etf_chunk(11)])
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", RECOMMEND, citations=[11]),
        tool_resp("search_documents", {"query": "ETF 장점", "doc_type": "ANY"}, id_="tu_2"),
        text_resp("ANSWER", CLEAN, citations=[11]),
    ])
    result = await outcome(make_agent(claude, retriever=retriever), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert retriever.queries == ["ETF란"]
    assert len(claude.calls) == 3


@pytest.mark.parametrize("retry_tool", [
    tool_resp("search_documents", {"query": "ETF 장점", "doc_type": "ANY"}),
    tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
], ids=["search_documents", "recalculate"])
async def test_ac3_tool_use_in_retry_after_direct_answer_is_not_executed(retry_tool: Any) -> None:
    """도구 없이 나온 첫 직답이 금지 표현이고 재시도 응답이 (아직 실행한 적 없는) 도구를 부르려 해도 실행하지 않는다.
    검색·계산 0번, 모델 2번에서 멈추고 FORBIDDEN_EXPRESSION으로 거부한다(U-1 결정 A)"""
    calc = FakeCalc()
    retriever = FakeRetriever([etf_chunk(11)])
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), retry_tool, text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude, calc, retriever), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert retriever.queries == []
    assert calc.calls == []
    assert len(claude.calls) == 2
    assert len(claude.responses) == 1


async def test_ac3_retry_call_sets_tool_choice_none() -> None:
    """재시도 호출에만 tool_choice none을 준다(U-1 결정). 첫 라운드·답 라운드 호출은 지금처럼 tool_choice를
    주지 않는다(키 없음 또는 기본값 auto). 도구 사용을 강제(any·tool)하거나 막지(none) 않는다"""
    claude = RecordingClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", RECOMMEND),
        text_resp("ANSWER", CLEAN),
    ])
    await outcome(make_agent(claude, retriever=FakeRetriever([etf_chunk(11)])), question())
    assert len(claude.calls) == 3
    assert claude.calls[2].get("tool_choice") == {"type": "none"}
    assert all(c.get("tool_choice") in (None, {"type": "auto"}) for c in claude.calls[:2]), [
        c.get("tool_choice") for c in claude.calls[:2]
    ]
    # 앞 messages의 tool_use 블록이 유효하도록 도구 정의는 그대로 보낸다. 호출 시점에 기록한 도구 정의로
    # 재시도와 금지 표현을 낸 직전 답 라운드를 직접 비교하고, 그 라운드도 첫 라운드와 같은지 본다(B4)
    tools = claude.tool_snapshots
    assert [t["name"] for t in tools[1]] == ["recalculate", "search_documents"], tools[1]
    assert tools[2] == tools[1]
    assert tools[1] == tools[0]


@pytest.mark.parametrize(("first_body", "keyword"), [(RECOMMEND, "추천"), (OTHER_STOCK, "추천")],
                         ids=["recommend", "other_stock"])
async def test_ac3_retry_correction_names_the_forbidden_reason(first_body: str, keyword: str) -> None:
    """재시도 호출 끝의 user 메시지(교정 지시)는 비어 있지 않고, 걸린 사유의 표현을 빼거나 바꾸라고 요청한다(B3).
    문장 전체는 고정하지 않는다(I-1). 사유 낱말(stock_recommendation→"추천")과 긍정 제거·대체
    지시를 담고, 걸린 표현을 그대로 쓰라거나 제거하지 말라거나 원문을 다시 출력하라는 말은 담지 않는다(asks_removal).
    판정이 약해지지 않았는지 반례(제거 금지·원문 재출력·다른 문장만 삭제)와 정상 예시로 먼저 확인한다
    (기획 검토 2차 B3, 3차 B1: 제거·대체의 대상이 걸린 표현이어야 한다)"""
    for ok in (
        f"{keyword} 표현을 빼고 다시 답해 주세요.",
        f"직전 답의 {keyword} 표현은 쓰지 말고, 대신 일반적인 말로 바꿔 답하세요.",
        f"나머지 내용은 그대로 두고 {keyword} 표현만 빼세요.",
        f"{keyword} 표현을 그대로 쓰지 마세요.",
        f"{keyword} 표현을 다른 말로 바꿔 쓰세요.",
        f"{keyword} 표현 대신 일반적인 이름을 쓰세요.",
        f"{keyword}이라는 표현 없이 다시 답하세요.",
    ):
        assert asks_removal(ok, keyword), ok
    for counter in (
        f"{keyword} 표현을 빼지 말고 원문 문장을 다시 출력하세요.",
        f"{keyword}을 지우지 말고 원문 문장을 다시 출력하세요.",
        f"{keyword} 표현을 제거하지 마세요.",
        f"{keyword} 표현을 바꾸지 말고 그대로 쓰세요.",
        f"{keyword} 표현이 있었어요. 같은 문장을 다시 쓰세요.",
        f"{keyword} 표현은 남겨 두고 마지막 인사말만 삭제하세요.",   # 3차 B1: 다른 문장만 삭제
        f"{keyword} 표현은 유지하고 문장 순서만 바꾸세요.",
        f"{keyword} 표현은 그대로 둔 채, 인사말을 지우세요.",
        # 이번 실행 B1: 걸린 표현은 쓰라고 하고 제거·대체의 대상은 다른 문장인 지시
        f"직전 답의 {keyword} 표현은 사용하세요. {keyword} 표현이 있는 문단의 인사말만 삭제하세요.",
        f"{keyword} 표현이 있는 문단의 인사말만 삭제하세요.",
        f"{keyword} 표현은 쓰고, 인사말을 지우세요.",
        f"{keyword} 표현이 든 문장 앞의 인사말을 지우세요.",
    ):
        assert not asks_removal(counter, keyword), counter
    claude = RecordingClaude([text_resp("ANSWER", first_body), text_resp("ANSWER", CLEAN)])
    await outcome(make_agent(claude), question())
    assert len(claude.snapshots) == 2
    correction = message_text(claude.snapshots[1][-1])
    assert claude.snapshots[1][-1]["role"] == "user"
    assert asks_removal(correction, keyword), correction


async def test_ac3_retry_citations_come_from_retry_answer_filtered_by_earlier_search() -> None:
    """첫 응답과 재시도 응답의 인용이 다르면 재시도 응답의 인용만 쓰고, 앞서 검색된 chunk만 남긴다.
    검색은 1번뿐이다(첫 응답 인용 11을 합치지 않고, 검색되지 않은 99는 뺀다)"""
    retriever = FakeRetriever([etf_chunk(11), etf_chunk(12)])
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", RECOMMEND, citations=[11]),
        text_resp("ANSWER", CLEAN, citations=[12, 99]),
    ])
    resp = await outcome(make_agent(claude, retriever=retriever), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.citation_chunk_ids == [12]
    assert retriever.queries == ["ETF란"]
    assert len(claude.calls) == 3


async def test_ac3_retry_citations_get_other_org_coverage_without_merging_discarded_answer() -> None:
    """B1 결정 A: 재시도 답이 한 운용사(A운용) 공시만 인용하면 기존 규칙대로 이미 검색된 다른 운용사(B운용)의
    가장 앞선 chunk(103, 104가 아니다)를 보충한다(FR-AI-03). 폐기된 첫 답의 인용(102)은 합치지 않는다. 검색은 1번뿐이다"""
    found = [
        disclosure_chunk(101, "A운용"), disclosure_chunk(102, "A운용"),
        disclosure_chunk(103, "B운용"), disclosure_chunk(104, "B운용"),
    ]
    retriever = FakeRetriever(found)
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "S&P500 추종 ETF 위험", "doc_type": "DISCLOSURE"}),
        text_resp("ANSWER", RECOMMEND, citations=[102]),
        text_resp("ANSWER", CLEAN, citations=[101]),
    ])
    resp = await outcome(make_agent(claude, retriever=retriever), question("ETF 위험이 뭐예요"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.citation_chunk_ids == [101, 103]
    assert len(retriever.queries) == 1
    assert len(claude.calls) == 3


async def test_ac3_retry_citations_at_cap_get_other_org_coverage_in_order() -> None:
    """B5: 재시도 답이 인용 상한(5건)을 채우고 유일한 공시(A운용 101)가 마지막이면, 기존 규칙대로 끝에서 두 번째
    인용(204)을 빼고 다른 운용사의 가장 앞선 공시(B운용 103)를 붙여 5건을 유지한다. 순서까지 본다.
    폐기된 첫 답의 인용(202·105)은 합치지 않는다"""
    found = [
        etf_chunk(201), etf_chunk(202), etf_chunk(203), etf_chunk(204),
        disclosure_chunk(101, "A운용"), disclosure_chunk(103, "B운용"), disclosure_chunk(105, "B운용"),
    ]
    retriever = FakeRetriever(found)
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "S&P500 추종 ETF 위험", "doc_type": "ANY"}),
        text_resp("ANSWER", RECOMMEND, citations=[202, 105]),
        text_resp("ANSWER", CLEAN, citations=[201, 202, 203, 204, 101]),
    ])
    resp = await outcome(make_agent(claude, retriever=retriever), question("ETF 위험이 뭐예요"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.citation_chunk_ids == [201, 202, 203, 101, 103]
    assert len(retriever.queries) == 1
    assert len(claude.calls) == 3


async def test_ac3_validation_failed_state_survives_retry() -> None:
    """계산이 422 VALIDATION_FAILED를 받은 뒤 금지 표현으로 재시도하면, 계산은 다시 하지 않고
    422의 표시값(1,000만원)을 숫자 근거로 인정하며 서버가 정한 CLARIFY(calcRunId·conditionChanges 없음)로 나간다"""
    body = json.loads(
        (CALC_EXAMPLES / "validation-failed-out-of-range-response.json").read_text(encoding="utf-8")
    )
    calc = FixtureCalc(error=CalcRejected(body["code"], body["errors"]))
    clean = "월 저축액은 1,000만원까지 넣을 수 있어요. 얼마로 할까요?"
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 12_000_000}),
        text_resp("CLARIFY", "월 저축액은 1,000만원까지 넣을 수 있어요. 삼성전자 주식을 추천해요."),
        text_resp("ANSWER", clean),
    ])
    resp = await outcome(make_agent(claude, calc), question("월 1,200만원으로 바꾸면?"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "CLARIFY"
    assert resp.content == clean
    assert resp.calc_run_id is None
    assert resp.condition_changes is None
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3


async def test_ac3_out_of_scope_retry_after_validation_failed_keeps_out_of_scope() -> None:
    """B1(2차) 결정 A: 계산이 422로 실패한 뒤 금지 표현으로 재시도했고 재시도 답이 정상 형식의 OUT_OF_SCOPE면,
    기존 assemble 보정대로 CLARIFY로 바꾸지 않고 문구표 범위 밖 안내(OUT_OF_SCOPE)로 나간다.
    계산은 다시 하지 않고 calcRunId·conditionChanges·인용은 없다"""
    body = json.loads(
        (CALC_EXAMPLES / "validation-failed-out-of-range-response.json").read_text(encoding="utf-8")
    )
    calc = FixtureCalc(error=CalcRejected(body["code"], body["errors"]))
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 12_000_000}),
        text_resp("CLARIFY", "월 저축액은 1,000만원까지 넣을 수 있어요. 삼성전자 주식을 추천해요."),
        text_resp("OUT_OF_SCOPE", f"{RETRY_SENTINEL} 이 질문은 다루지 않아요."),
    ])
    resp = await outcome(make_agent(claude, calc), question("월 1,200만원으로 바꾸면?"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.content == out_of_scope_content()
    assert not leaked(resp.content, RETRY_SENTINEL), leaked(resp.content, RETRY_SENTINEL)
    assert resp.calc_run_id is None
    assert resp.condition_changes is None
    assert resp.citation_chunk_ids == []
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3


async def test_ac3_tax_out_of_scope_retry_after_validation_failed_keeps_tax_copy() -> None:
    """B1(2차) 결정 A, 기획 검토 2차 B1: 422 뒤 재시도 답이 정상 형식의 OUT_OF_SCOPE이고 본문에 세금 전용 문구
    (outOfScope.tax)가 들어 있으면, 기존 보정대로 그 문구만 나간다(기본 안내로 바꾸지 않고 모델의 다른 문장은 버린다).
    계산은 다시 하지 않고 calcRunId·conditionChanges는 없다. 3차 B3: 금지 답변 전에 실제 검색 라운드가 있어 chunk 11이
    이번 요청에서 검색됐고 재시도 답이 그 11을 인용해도, 범위 밖 답이므로 인용은 빈 배열이다(일반 답의 인용 필터만
    적용하면 [11]이 나간다). 검색 1번, 계산 1번, 모델 4번(검색·계산·금지 답·재시도)"""
    body = json.loads(
        (CALC_EXAMPLES / "validation-failed-out-of-range-response.json").read_text(encoding="utf-8")
    )
    calc = FixtureCalc(error=CalcRejected(body["code"], body["errors"]))
    tax_copy = load_copy().get("outOfScope.tax")
    retriever = FakeRetriever([etf_chunk(11)])
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF 세금", "doc_type": "EDU"}),
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 12_000_000}, id_="tu_2"),
        text_resp("CLARIFY", "월 저축액은 1,000만원까지 넣을 수 있어요. 삼성전자 주식을 추천해요.", citations=[11]),
        text_resp("OUT_OF_SCOPE", f"{RETRY_SENTINEL} {tax_copy}", citations=[11]),
    ])
    resp = await outcome(make_agent(claude, calc, retriever), question("월 1,200만원으로 바꾸면 세금은요?"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.content == tax_copy
    assert resp.content != out_of_scope_content()
    assert resp.calc_run_id is None
    assert resp.condition_changes is None
    assert resp.citation_chunk_ids == []
    assert retriever.queries == ["ETF 세금"]
    assert len(calc.calls) == 1
    assert len(claude.calls) == 4


async def test_ac3_retry_citations_dedupe_and_cap_in_first_seen_order() -> None:
    """기획 검토 2차 B7: 재시도 답의 인용에 중복과 상한(5건) 초과가 함께 있으면 기존 보정대로 최초 등장 순서를
    유지해 중복을 빼고 5건에서 자른다. 3차 B2: 인용 순서는 검색 순서(11~16)와도 ID 오름차순과도 달라서, 정렬한 뒤
    자르는 구현([11, 12, 13, 14, 15])이나 검색 순서로 되돌리는 구현은 통과하지 않는다. 순서까지 본다.
    폐기된 첫 답의 인용(13)은 합치지 않는다. 검색은 1번뿐이다"""
    retriever = FakeRetriever([etf_chunk(i) for i in range(11, 17)])
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", RECOMMEND, citations=[13]),
        text_resp("ANSWER", CLEAN, citations=[15, 15, 12, 16, 11, 14, 13]),
    ])
    resp = await outcome(make_agent(claude, retriever=retriever), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.citation_chunk_ids == [15, 12, 16, 11, 14]
    assert retriever.queries == ["ETF란"]
    assert len(claude.calls) == 3


# ---- AC-4: 사유와 재시도 여부를 로그에 남기고 본문 전문은 남기지 않는다 ----

@pytest.mark.parametrize(("first_body", "reason"), [(RECOMMEND, "stock_recommendation"),
                                                     (OTHER_STOCK, "stock_recommendation")],
                         ids=["recommend", "other_stock"])
async def test_ac4_retry_logs_reason_and_attempt_without_body(
    first_body: str, reason: str, caplog: pytest.LogCaptureFixture
) -> None:
    """첫 응답 거부 → 재시도 성공: 사유(stock_recommendation)와 attempt=1이 INFO 이상 레코드
    정확히 1건으로 남고, 거부된 첫 본문도 사용자에게 나간 재시도 본문도 로그에 남지 않는다"""
    caplog.set_level(logging.DEBUG)
    body = f"{BODY_SENTINEL} {first_body}"
    retry_body = f"{RETRY_SENTINEL} {CLEAN}"
    claude = FakeClaude([text_resp("ANSWER", body), text_resp("ANSWER", retry_body)])
    resp = await outcome(make_agent(claude), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.content == retry_body
    records = reason_records(caplog)
    assert len(records) == 1, [r.getMessage() for r in records]
    assert log_fields(records[0].getMessage()) == (reason, "1"), records[0].getMessage()   # 값 경계까지(3차 B6)
    assert records[0].levelno >= logging.INFO
    assert not leaked(caplog.text, BODY_SENTINEL), leaked(caplog.text, BODY_SENTINEL)
    assert not leaked(caplog.text, RETRY_SENTINEL), leaked(caplog.text, RETRY_SENTINEL)


@pytest.mark.parametrize("raw", [
    json.dumps({"message_type": "ANSWER", "content": RETRY_SENTINEL}, ensure_ascii=False),   # 필수 키 누락
    json.dumps(   # 필드 타입 오류
        {"message_type": "ANSWER", "content": [RETRY_SENTINEL], "citation_chunk_ids": []}, ensure_ascii=False
    ),
    f'{{"message_type": "ANSWER", "content": "{RETRY_SENTINEL} {GENERIC}는 지수를',   # B8: 중간에서 잘린 JSON
    # D-2(S4 B3): 토큰 상한에 걸려 잘린 JSON(텍스트 있음)은 MAX_TOKENS가 아니라 P-17대로 BAD_OUTPUT이다
    SimpleNamespace(stop_reason="max_tokens", content=[
        BetaTextBlock(type="text", text=f'{{"message_type": "ANSWER", "content": "{RETRY_SENTINEL} {GENERIC}는 지수를'),
    ]),
], ids=["missing_key", "wrong_type", "truncated_json", "max_tokens_truncated_json"])
async def test_ac4_malformed_retry_response_does_not_leak_body(raw: Any, caplog: pytest.LogCaptureFixture) -> None:
    """재시도 응답이 형식 오류(필수 키 누락·필드 타입 오류·잘린 JSON)면 BAD_OUTPUT으로 끝나고, 그 원문(표지 문자열)은
    로그에도 AgentError 문자열에도 없다. 거부된 첫 답의 표지도 없다. 원문·JSON 이스케이프 형태를 모두 본다(B4).
    깨끗한 세 번째 응답이 있어도 모델은 2번만 불린다. 3차 B5: 재시도가 형식 오류로 끝나도 첫 거부의 사유·시도
    번호(stock_recommendation, attempt=1) 레코드가 정확히 1건 남는다(재시도 결과를 본 뒤에만 사유를 기록하면 빠진다)"""
    caplog.set_level(logging.DEBUG)
    claude = FakeClaude([
        text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"),
        raw if not isinstance(raw, str) else raw_text_resp(raw),
        text_resp("ANSWER", CLEAN),
    ])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "BAD_OUTPUT"
    messages = [r.getMessage() for r in reason_records(caplog)]
    assert [log_fields(m) for m in messages] == [("stock_recommendation", "1")], messages
    assert all(r.levelno >= logging.INFO for r in reason_records(caplog))
    assert not leaked(str(result), BODY_SENTINEL, RETRY_SENTINEL), leaked(str(result), BODY_SENTINEL, RETRY_SENTINEL)
    assert not leaked(caplog.text, BODY_SENTINEL, RETRY_SENTINEL), leaked(caplog.text, BODY_SENTINEL, RETRY_SENTINEL)
    assert len(claude.calls) == 2


# ---- AC-5: 첫 응답이 깨끗하면 모델 호출이 늘지 않는다 ----

async def test_ac5_clean_first_answer_calls_model_once() -> None:
    """(회귀 보호) 깨끗한 첫 응답은 그대로 나가고 모델은 1번만 불린다"""
    claude = FakeClaude([text_resp("ANSWER", CLEAN)])
    resp = await make_agent(claude).run(question())
    assert resp.content == CLEAN
    assert len(claude.calls) == 1


async def test_ac5_clean_answer_after_tool_does_not_add_model_call() -> None:
    """(회귀 보호) 도구 1번 + 깨끗한 답이면 모델 호출은 2번 그대로다"""
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", CLEAN, citations=[11]),
    ])
    resp = await make_agent(claude, retriever=FakeRetriever([etf_chunk(11)])).run(question())
    assert resp.content == CLEAN
    assert len(claude.calls) == 2


async def test_ac5_clean_answer_after_recalculate_does_not_add_model_call() -> None:
    """(회귀 보호) 계산 성공 + 깨끗한 답이면 계산 1번, 모델 2번으로 RECALC_RESULT가 나간다"""
    calc = FakeCalc()
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요."),
    ])
    resp = await make_agent(claude, calc).run(question("월 50만원으로 바꾸면?"))
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 77
    assert len(calc.calls) == 1
    assert len(claude.calls) == 2


@pytest.mark.parametrize("content", [
    "KODEX 미국S&P500은 지수를 따라가요.",          # ETF 상품명은 그대로 통과하고 재시도하지 않는다(CF-07)
    f"{GENERIC}는 지수를 따라가요. 이 상품을 추천하지 않아요.",   # 종목명 없는 부정문은 금지 표현이 아니다(CF-07)
])
async def test_ac5_product_name_or_negation_does_not_retry(content: str) -> None:
    """(회귀 보호) 거부 사유가 아닌 본문은 재시도하지 않는다"""
    claude = FakeClaude([text_resp("ANSWER", content)])
    resp = await make_agent(claude).run(question())
    assert isinstance(resp, ChatResponse)
    assert len(claude.calls) == 1


async def test_ac7_clean_request_right_after_a_recovered_retry_is_not_retried() -> None:
    """같은 agent에서 요청 A가 재시도로 살아난 직후, 요청 B의 첫 답이 깨끗하면 B는 그 답을 그대로 내고
    모델을 1번만 부른다(직전 요청의 교정 상태가 남지 않는다). 남은 응답은 쓰지 않는다"""
    clean_b = f"{GENERIC}는 값이 오르내릴 수 있어요."
    claude = FakeClaude([
        text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", CLEAN),
        text_resp("ANSWER", clean_b), text_resp("ANSWER", "쓰이지 않아야 하는 답"),
    ])
    agent = make_agent(claude)
    first = await outcome(agent, question())
    assert isinstance(first, ChatResponse), first
    assert first.content == CLEAN
    second = await outcome(agent, question("ETF 위험은요?"))
    assert isinstance(second, ChatResponse), second
    assert second.content == clean_b
    assert len(claude.calls) == 3
    assert len(claude.responses) == 1


async def test_ac7_next_request_after_recovered_retry_has_no_tool_choice_none() -> None:
    """B7: 같은 agent에서 요청 A가 재시도(tool_choice none)로 살아난 직후, 요청 B의 모델 호출에는 tool_choice가
    없거나 auto다(직전 요청의 재시도 설정이 남지 않는다). B는 검색 1번 + 깨끗한 답으로 모델 2번이다"""
    retriever = FakeRetriever([etf_chunk(11)])
    clean_b = f"{GENERIC}는 값이 오르내릴 수 있어요."
    claude = FakeClaude([
        text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", CLEAN),
        tool_resp("search_documents", {"query": "ETF 위험", "doc_type": "EDU"}),
        text_resp("ANSWER", clean_b, citations=[11]),
    ])
    agent = make_agent(claude, retriever=retriever)
    first = await outcome(agent, question())
    assert isinstance(first, ChatResponse), first
    assert claude.calls[1].get("tool_choice") == {"type": "none"}
    second = await outcome(agent, question("ETF 위험은요?"))
    assert isinstance(second, ChatResponse), second
    assert second.content == clean_b
    assert len(claude.calls) == 4
    assert all(c.get("tool_choice") in (None, {"type": "auto"}) for c in claude.calls[2:]), [
        c.get("tool_choice") for c in claude.calls[2:]
    ]
    assert retriever.queries == ["ETF 위험"]


@pytest.mark.parametrize("retry_outcome", ["rejected", "api_error"])
async def test_ac7_next_request_after_failed_retry_has_no_tool_choice_none(retry_outcome: str) -> None:
    """기획 검토 3차 B9: 같은 agent에서 요청 A가 재시도(tool_choice none) 뒤 최종 거부되거나 재시도 호출이 API 오류로
    끝난 뒤에도, 요청 B의 일반 호출(검색이 필요한 첫 호출, 검색 뒤 답 호출)은 tool_choice가 없거나 auto다(예외 경로에서
    재시도 설정이 남지 않는다). B는 검색 1번 + 깨끗한 답(인용 [11])으로 정상 처리되고 모델은 2번이다"""
    retriever = FakeRetriever([etf_chunk(11)])
    clean_b = f"{GENERIC}는 값이 오르내릴 수 있어요."
    error = anthropic.APIConnectionError(request=httpx.Request("POST", "https://api.anthropic.com/v1/messages"))
    retry_item: Any = text_resp("ANSWER", RECOMMEND) if retry_outcome == "rejected" else error
    claude = ScriptedClaude([
        text_resp("ANSWER", RECOMMEND), retry_item,
        tool_resp("search_documents", {"query": "ETF 위험", "doc_type": "EDU"}),
        text_resp("ANSWER", clean_b, citations=[11]),
    ])
    agent = make_agent(claude, retriever=retriever)
    try:
        first: ChatResponse | AgentError | anthropic.APIError = await outcome(agent, question())
    except anthropic.APIError as e:
        first = e
    if retry_outcome == "rejected":
        assert isinstance(first, AgentError) and first.code == "FORBIDDEN_EXPRESSION", first
    else:
        assert isinstance(first, anthropic.APIConnectionError), first
    assert len(claude.calls) == 2
    assert claude.calls[1].get("tool_choice") == {"type": "none"}
    second = await outcome(agent, question("ETF 위험은요?"))
    assert isinstance(second, ChatResponse), second
    assert second.content == clean_b
    assert second.citation_chunk_ids == [11]
    assert retriever.queries == ["ETF 위험"]
    assert len(claude.calls) == 4
    assert all(c.get("tool_choice") in (None, {"type": "auto"}) for c in claude.calls[2:]), [
        c.get("tool_choice") for c in claude.calls[2:]
    ]


async def test_ac5_out_of_scope_body_with_recommendation_does_not_retry() -> None:
    """(회귀 보호) OUT_OF_SCOPE 본문은 문구표 문장으로 바뀌므로 검사·재시도하지 않는다"""
    claude = FakeClaude([text_resp("OUT_OF_SCOPE", "삼성전자 주식을 추천해요")])
    resp = await make_agent(claude).run(question("삼성전자 살까요?"))
    assert resp.message_type == "OUT_OF_SCOPE"
    assert len(claude.calls) == 1


@pytest.mark.parametrize("raw", [
    json.dumps({"message_type": "ANSWER", "content": CLEAN}, ensure_ascii=False),   # 필수 키 누락
    f'{{"message_type": "ANSWER", "content": "{CLEAN}',   # 잘린 JSON
], ids=["missing_key", "truncated_json"])
async def test_ac5_bad_output_on_first_answer_is_not_retried(raw: str) -> None:
    """(회귀 보호) 기획 검토 2차 B9: 첫 응답이 형식 오류(본문은 깨끗)면 교정 재시도 대상이 아니다. 정상 답이
    대기해 있어도 BAD_OUTPUT으로 끝나고 모델은 1번이며 다음 응답은 쓰지 않는다"""
    claude = FakeClaude([raw_text_resp(raw), text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "BAD_OUTPUT"
    assert len(claude.calls) == 1
    assert len(claude.responses) == 1


# ---- AC-6: 재시도 대상은 금지 표현뿐이고, 재시도 응답도 같은 검사를 받는다 ----

async def test_ac5_number_mismatch_on_first_answer_is_not_retried() -> None:
    """(회귀 보호) 근거 없는 금액(NUMBER_MISMATCH)은 재시도하지 않고 바로 거부한다"""
    claude = FakeClaude([text_resp("ANSWER", "월 999만원을 넣으면 돼요.")])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "NUMBER_MISMATCH"
    assert len(claude.calls) == 1


async def test_ac6_retry_answer_with_unsupported_number_is_rejected_as_number_mismatch() -> None:
    """재시도 응답은 금지 표현이 없어도 숫자 대조를 받는다. 근거 없는 금액이면 NUMBER_MISMATCH"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", "월 999만원을 넣으면 돼요.")])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "NUMBER_MISMATCH"
    assert len(claude.calls) == 2


async def test_ac6_amount_from_discarded_first_answer_is_not_number_evidence() -> None:
    """기획 검토 3차 B7: 요청·도구 결과에 없는 금액(월 999만원)을 첫 답이 추천 표현과 함께 말하고, 재시도 답이 추천
    표현만 빼고 같은 금액을 반복하면 NUMBER_MISMATCH다. 폐기한 첫 답은 숫자 근거가 아니다(근거는 이번 요청의
    planContext display·계산 표시값·요청 text뿐). 모델 2번이고 준비된 세 번째 응답은 쓰지 않는다"""
    claude = FakeClaude([
        text_resp("ANSWER", "월 999만원을 넣으면 돼요. 삼성전자 주식을 추천해요."),
        text_resp("ANSWER", "월 999만원을 넣으면 돼요."),
        text_resp("ANSWER", CLEAN),
    ])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "NUMBER_MISMATCH"
    assert len(claude.calls) == 2
    assert len(claude.responses) == 1


@pytest.mark.parametrize(("amount", "code"), [("약 2,501만원", None), ("약 2,999만원", "NUMBER_MISMATCH")],
                         ids=["display_amount", "unsupported_amount"])
async def test_ac6_retry_answer_keeps_successful_calc_display_as_number_evidence(amount: str, code: str | None) -> None:
    """기획 검토 2차 B6: 계산 성공 응답(fixture, calcRunId 552)의 display에만 있는 금액을 재시도 답이 인용하면
    추가 계산 없이 그 계산의 RECALC_RESULT로 재시도 본문이 나간다. 같은 시나리오에서 금액만 근거 없는 값으로
    바꾸면 NUMBER_MISMATCH다. 계산 1번, 모델 3번"""
    calc = FixtureCalc()
    retry_body = f"월 50만원으로 바꾸면 평균 {amount}이 모여요."
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요. 삼성전자 주식을 추천해요."),
        text_resp("ANSWER", retry_body),
    ])
    result = await outcome(make_agent(claude, calc), question("월 50만원으로 바꾸면?"))
    if code is None:
        assert isinstance(result, ChatResponse), result
        assert result.message_type == "RECALC_RESULT"
        assert result.calc_run_id == 552
        assert result.condition_changes == {"monthly_saving_amount": 500_000}
        assert result.content == retry_body
    else:
        assert isinstance(result, AgentError), result
        assert result.code == code
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3


async def test_ac6_retry_answer_with_forbidden_and_unsupported_number_is_forbidden_expression() -> None:
    """첫 응답은 금지 표현만(숫자 없음), 재시도 응답은 추천 표현과 근거 없는 금액을 함께 담는다.
    재시도 응답도 기존 검사 순서(종목 추천 검사 → 숫자 대조)를 따라 FORBIDDEN_EXPRESSION으로 거부되고 모델은 2번이다"""
    claude = FakeClaude([
        text_resp("ANSWER", RECOMMEND),
        text_resp("ANSWER", f"월 999만원을 넣으면 돼요. {RECOMMEND}"),
        text_resp("ANSWER", CLEAN),
    ])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 2


async def test_ac6_retry_call_api_error_propagates_without_third_call() -> None:
    """재시도 모델 호출이 API 오류를 내면 첫 호출과 같은 경로(anthropic.APIError가 그대로 올라감)로 끝나고
    모델을 세 번째로 부르지 않는다"""
    error = anthropic.APIConnectionError(request=httpx.Request("POST", "https://api.anthropic.com/v1/messages"))
    claude = ScriptedClaude([text_resp("ANSWER", RECOMMEND), error, text_resp("ANSWER", CLEAN)])
    raised: BaseException | None = None
    try:
        await make_agent(claude).run(question())
    except (anthropic.APIError, AgentError) as e:
        raised = e
    assert isinstance(raised, anthropic.APIConnectionError), raised
    assert len(claude.calls) == 2


@pytest.mark.parametrize(("retry_resp", "code"), [
    (SimpleNamespace(stop_reason="max_tokens", content=[]), "MAX_TOKENS"),
    (raw_text_resp(json.dumps({"message_type": "ANSWER", "content": "답"})), "BAD_OUTPUT"),
], ids=["max_tokens", "schema_error"])
async def test_ac6_abnormal_retry_response_follows_existing_failure_path(retry_resp: Any, code: str) -> None:
    """재시도 응답이 max_tokens거나 필수 키가 빠진 형식 오류면 첫 응답과 같은 실패 코드로 끝나고,
    깨끗한 세 번째 응답이 준비돼 있어도 모델을 더 부르지 않는다"""
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), retry_resp, text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == code
    assert len(claude.calls) == 2


async def test_ac6_refusal_on_retry_follows_existing_refusal_path() -> None:
    """재시도 응답이 refusal이면 첫 호출의 refusal과 같은 경로로 문구표 범위 밖 안내(OUT_OF_SCOPE)가 나가고
    모델 본문은 남지 않는다. 깨끗한 세 번째 응답이 있어도 모델을 더 부르지 않는다(P-10)"""
    calc = FakeCalc()
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"),
        SimpleNamespace(stop_reason="refusal", content=[]),
        text_resp("ANSWER", CLEAN),
    ])
    resp = await outcome(make_agent(claude, calc), question("월 50만원으로 바꾸면?"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.content == out_of_scope_content()
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert not leaked(resp.content, BODY_SENTINEL), leaked(resp.content, BODY_SENTINEL)
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3


# ---- 이전 실행 기획 검토(p2) plan-2~plan-8 보완. S2 기준 변경으로 minor지만 구현 전에 테스트로 둔다 ----

@pytest.mark.parametrize(("body", "reason"), [(RECOMMEND, "stock_recommendation"),
                                               (OTHER_STOCK, "stock_recommendation")],
                         ids=["recommend", "other_stock"])
async def test_ac4_same_reason_rejected_twice_logs_both_attempts_without_body(
    body: str, reason: str, caplog: pytest.LogCaptureFixture
) -> None:
    """plan-2: 첫 답과 재시도 답이 같은 사유로 거부돼도 시도마다 레코드가 1건씩 남는다(합치지 않는다).
    attempt=1·attempt=2가 같은 사유로 각각 남고, 두 본문(서로 다른 표지)은 로그·AgentError 문자열에 없다"""
    caplog.set_level(logging.DEBUG)
    claude = FakeClaude([
        text_resp("ANSWER", f"{BODY_SENTINEL} {body}"),
        text_resp("ANSWER", f"{RETRY_SENTINEL} {body}"),
    ])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    messages = [r.getMessage() for r in reason_records(caplog)]
    assert [log_fields(m) for m in messages] == [(reason, "1"), (reason, "2")], messages
    assert all(r.levelno >= logging.INFO for r in reason_records(caplog))
    assert not leaked(caplog.text, BODY_SENTINEL, RETRY_SENTINEL), leaked(caplog.text, BODY_SENTINEL, RETRY_SENTINEL)
    assert not leaked(str(result), BODY_SENTINEL, RETRY_SENTINEL), leaked(str(result), BODY_SENTINEL, RETRY_SENTINEL)
    assert len(claude.calls) == 2


async def test_ac4_first_rejection_is_logged_even_when_retry_call_raises(caplog: pytest.LogCaptureFixture) -> None:
    """plan-3: 재시도 모델 호출이 API 오류로 끝나도 첫 거부의 사유·시도 번호(stock_recommendation, attempt=1) 레코드가
    정확히 1건 남고 첫 본문은 로그에 없다. API 오류는 그대로 올라가고 모델은 2번이다(P-10)"""
    caplog.set_level(logging.DEBUG)
    error = anthropic.APIConnectionError(request=httpx.Request("POST", "https://api.anthropic.com/v1/messages"))
    claude = ScriptedClaude([text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"), error, text_resp("ANSWER", CLEAN)])
    raised: BaseException | None = None
    try:
        await make_agent(claude).run(question())
    except (anthropic.APIError, AgentError) as e:
        raised = e
    assert isinstance(raised, anthropic.APIConnectionError), raised
    messages = [r.getMessage() for r in reason_records(caplog)]
    assert [log_fields(m) for m in messages] == [("stock_recommendation", "1")], messages
    assert all(r.levelno >= logging.INFO for r in reason_records(caplog))
    assert not leaked(caplog.text, BODY_SENTINEL), leaked(caplog.text, BODY_SENTINEL)
    assert len(claude.calls) == 2


async def test_ac3_tool_use_with_text_block_in_retry_response_is_not_executed() -> None:
    """plan-4: 재시도 응답이 stop_reason=tool_use이고 content에 텍스트 블록과 도구 블록이 함께 있어도, 텍스트를 먼저
    파싱해 BAD_OUTPUT으로 끝내지 않고 PD-009대로 FORBIDDEN_EXPRESSION이다. 계산은 첫 1번뿐이고 모델은 3번에서 멈춘다"""
    calc = FakeCalc()
    mixed = SimpleNamespace(stop_reason="tool_use", content=[
        BetaTextBlock(type="text", text="계산이 필요해요."),
        BetaToolUseBlock(type="tool_use", id="tu_2", name="recalculate",
                         input={**NULLS, "monthly_saving_amount": 600_000}),
    ])
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요. 삼성전자 주식을 추천해요."),
        mixed,
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요."),
    ])
    retriever = FakeRetriever([etf_chunk(11)])
    result = await outcome(make_agent(claude, calc, retriever), question("월 50만원으로 바꾸면?"))
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(calc.calls) == 1
    assert retriever.queries == []
    assert len(claude.calls) == 3
    assert len(claude.responses) == 1


async def test_ac7_overlapping_retry_cannot_cite_chunks_searched_by_another_request() -> None:
    """plan-5: 검색하지 않은 요청 A의 재시도 호출을 보류한 동안 같은 agent의 요청 B가 chunk 11을 검색한다. 이어 도착한
    A의 재시도 답이 11을 인용해도 A의 인용은 빈 배열이고(이번 요청에서 검색된 chunk만), B의 인용은 [11]이다"""
    qa, qb = "ETF가 뭐예요", "ETF 위험은요?"
    clean_a, clean_b = f"{GENERIC}는 지수를 따라가요.", f"{GENERIC}는 값이 오르내릴 수 있어요."
    retriever = FakeRetriever([etf_chunk(11)])
    claude = GatedClaude(
        {
            qa: [text_resp("ANSWER", RECOMMEND, citations=[11]), text_resp("ANSWER", clean_a, citations=[11])],
            qb: [
                tool_resp("search_documents", {"query": "ETF 위험", "doc_type": "EDU"}),
                text_resp("ANSWER", clean_b, citations=[11]),
            ],
        },
        gated=qa,
    )
    agent = make_agent(claude, retriever=retriever)
    task_a = asyncio.create_task(outcome(agent, question(qa)))
    waiter = asyncio.create_task(claude.retry_started.wait())
    await asyncio.wait({task_a, waiter}, timeout=1, return_when=asyncio.FIRST_COMPLETED)
    waiter.cancel()
    assert claude.retry_started.is_set(), f"요청 A가 재시도하지 않았다: {task_a.result() if task_a.done() else None}"
    b = await asyncio.wait_for(outcome(agent, question(qb)), timeout=1)
    claude.release.set()
    a = await asyncio.wait_for(task_a, timeout=1)
    assert isinstance(a, ChatResponse), a
    assert isinstance(b, ChatResponse), b
    assert a.content == clean_a
    assert a.citation_chunk_ids == []
    assert b.citation_chunk_ids == [11]
    assert retriever.queries == ["ETF 위험"]
    assert claude.calls_by == {qa: 2, qb: 2}


async def test_ac1_direct_clarify_with_recommendation_then_clean_retry_returns_clarify() -> None:
    """plan-6: 도구 호출 없이(계산 422 상태 없이) 나온 첫 CLARIFY 본문에 긍정 추천 표현이 있고 재시도 CLARIFY 본문이
    깨끗하면, 재시도의 확인 질문이 CLARIFY ChatResponse로 나간다. 모델 2번"""
    clean = "어떤 조건을 바꾸고 싶으세요? 월 저축액과 기간 중에서 골라 주세요."
    claude = FakeClaude([
        text_resp("CLARIFY", "삼성전자 주식을 추천해요. 어떤 조건을 바꿀까요?"), text_resp("CLARIFY", clean),
    ])
    resp = await outcome(make_agent(claude), question("바꾸고 싶어요"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "CLARIFY"
    assert resp.content == clean
    assert resp.calc_run_id is None
    assert len(claude.calls) == 2


@pytest.mark.parametrize("raw", [
    json.dumps({"message_type": "ANSWER", "content": RECOMMEND}, ensure_ascii=False),   # 필수 키 누락
    f'{{"message_type": "ANSWER", "content": "{RECOMMEND}',   # 잘린 JSON
], ids=["missing_key", "truncated_json"])
async def test_ac5_bad_output_with_forbidden_text_on_first_answer_is_not_retried(raw: str) -> None:
    """(회귀 보호) plan-7: 첫 응답이 형식 오류이면 원시 텍스트에 추천 표현이 섞여 있어도 교정 재시도 대상이 아니다(P-1:
    M-4 본문 검사는 형식 검증을 통과한 답에만 한다). BAD_OUTPUT, 모델 1번, 다음 응답은 쓰지 않는다"""
    claude = FakeClaude([raw_text_resp(raw), text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "BAD_OUTPUT"
    assert len(claude.calls) == 1
    assert len(claude.responses) == 1


async def test_ac6_retry_answer_keeps_request_text_as_number_evidence() -> None:
    """plan-8: 계산·planContext 없이 요청 text에만 있는 금액(월 37만원)을 재시도 답이 인용하면, 재시도에서도 요청 text가
    숫자 근거라서 정상 응답으로 나간다(재시도에서만 근거를 잃으면 NUMBER_MISMATCH). 모델 2번"""
    retry_body = "월 37만원씩 모으려는 계획이군요."
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", retry_body)])
    resp = await outcome(make_agent(claude), question("월 37만원씩 모으려고 해요"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.content == retry_body
    assert len(claude.calls) == 2


# ---- 이번 실행 기획 검토(p0) B2~B8 보완. 모두 test_gap이라 정책 결정은 바뀌지 않는다 ----

@pytest.mark.parametrize("retry_type", ["RECALC_RESULT", "EXPLANATION"])
async def test_ac1_retry_type_without_server_fact_is_corrected_to_answer(retry_type: str) -> None:
    """이번 실행 B2: 계산 도구를 실행하지 않은 QUESTION에서 첫 답이 추천 표현으로 걸리고 재시도 모델이 깨끗한 본문을
    RECALC_RESULT·EXPLANATION으로 내면, 첫 응답과 같은 assemble 보정대로 ANSWER로 나가고 calcRunId·conditionChanges는
    비어 있다. 본문은 재시도 본문 그대로, 계산 0번, 모델 2번"""
    calc = FakeCalc()
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp(retry_type, CLEAN)])
    resp = await outcome(make_agent(claude, calc), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "ANSWER"
    assert resp.content == CLEAN
    assert resp.calc_run_id is None
    assert resp.condition_changes is None
    assert calc.calls == []
    assert len(claude.calls) == 2


async def test_ac2_forbidden_retry_after_recalculate_stops_without_more_calls() -> None:
    """이번 실행 B3: 계산 뒤 첫 답은 추천 표현, 재시도 답도 다른 문장의 종목 추천이면 FORBIDDEN_EXPRESSION으로 끝난다.
    계산 1번, 모델 3번(계산 라운드·첫 답·재시도)이고 준비된 깨끗한 네 번째 답은 쓰지 않는다(일반 루프로 돌아가지 않는다)"""
    calc = FakeCalc()
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요. 삼성전자 주식을 추천해요."),
        text_resp("ANSWER", f"월 저축액을 바꾼 결과예요. {OTHER_STOCK}"),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요."),
    ])
    result = await outcome(make_agent(claude, calc), question("월 50만원으로 바꾸면?"))
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    assert len(calc.calls) == 1
    assert len(claude.calls) == 3
    assert len(claude.responses) == 1


async def test_ac3_retry_after_recalculate_and_search_keeps_calc_and_citations() -> None:
    """이번 실행 B4: 한 요청에서 계산(calcRunId 77)과 검색(chunk 11)이 모두 성공하고 첫 답이 금지 표현으로 걸린 뒤
    깨끗한 재시도 답이 [11]을 인용하면, RECALC_RESULT·calcRunId 77·conditionChanges와 인용 [11]이 함께 남는다.
    계산 1번, 검색 1번, 모델 4번(계산·검색·첫 답·재시도)"""
    calc = FakeCalc()
    retriever = FakeRetriever([etf_chunk(11)])
    retry_body = "월 저축액을 바꾼 결과예요. ETF는 여러 자산을 묶은 펀드예요."
    claude = FakeClaude([
        tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}, id_="tu_2"),
        text_resp("ANSWER", "월 저축액을 바꾼 결과예요. 삼성전자 주식을 추천해요.", citations=[11]),
        text_resp("ANSWER", retry_body, citations=[11]),
    ])
    resp = await outcome(make_agent(claude, calc, retriever), question("월 50만원으로 바꾸면?"))
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 77
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}
    assert resp.content == retry_body
    assert resp.citation_chunk_ids == [11]
    assert len(calc.calls) == 1
    assert retriever.queries == ["ETF란"]
    assert len(claude.calls) == 4


@pytest.mark.parametrize("retry_tool", [
    tool_resp("search_documents", {"query": "ETF 장점", "doc_type": "ANY"}),
    tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
], ids=["search_documents", "recalculate"])
async def test_ac4_first_rejection_is_logged_when_retry_response_is_tool_use(
    retry_tool: Any, caplog: pytest.LogCaptureFixture
) -> None:
    """이번 실행 B5: 첫 답이 추천 표현으로 거부되고 재시도 모델이 도구를 요청해도(PD-009로 FORBIDDEN_EXPRESSION),
    첫 거부의 사유·시도 번호(stock_recommendation, attempt=1) 레코드가 정확히 1건 남고 첫 본문은 로그·AgentError 문자열에
    없다. 검색·계산 0번, 모델 2번이고 준비된 세 번째 답은 쓰지 않는다"""
    caplog.set_level(logging.DEBUG)
    calc = FakeCalc()
    retriever = FakeRetriever([etf_chunk(11)])
    claude = FakeClaude([text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}"), retry_tool, text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude, calc, retriever), question())
    assert isinstance(result, AgentError), result
    assert result.code == "FORBIDDEN_EXPRESSION"
    messages = [r.getMessage() for r in reason_records(caplog)]
    assert [log_fields(m) for m in messages] == [("stock_recommendation", "1")], messages
    assert all(r.levelno >= logging.INFO for r in reason_records(caplog))
    assert not leaked(caplog.text, BODY_SENTINEL), leaked(caplog.text, BODY_SENTINEL)
    assert not leaked(str(result), BODY_SENTINEL), leaked(str(result), BODY_SENTINEL)
    assert retriever.queries == []
    assert calc.calls == []
    assert len(claude.calls) == 2
    assert len(claude.responses) == 1


async def test_ac4_retry_number_mismatch_does_not_put_whole_body_in_error_or_log(
    caplog: pytest.LogCaptureFixture
) -> None:
    """이번 실행 B6: 첫 답이 추천 표현으로 거부되고 형식이 유효한 재시도 답의 content가 근거 없는 금액 하나('999만원')뿐이면
    NUMBER_MISMATCH로 끝난다. 이때 재시도 본문 전체(=불일치 토큰)는 로그에도 AgentError 문자열에도 없다
    (기존 숫자 오류 detail을 그대로 써 'NUMBER_MISMATCH: 999만원'을 만들면 실패한다). 모델 2번"""
    caplog.set_level(logging.DEBUG)
    retry_body = "999만원"
    claude = FakeClaude([text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", retry_body), text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "NUMBER_MISMATCH"
    assert not leaked(str(result), retry_body), str(result)
    assert not leaked(caplog.text, retry_body), leaked(caplog.text, retry_body)
    assert len(claude.calls) == 2
    assert len(claude.responses) == 1


@pytest.mark.parametrize(("amount", "code"), [("약 1,712만원", None), ("약 1,799만원", "NUMBER_MISMATCH")],
                         ids=["plan_context_amount", "unsupported_amount"])
async def test_ac6_explain_retry_answer_keeps_plan_context_display_as_number_evidence(
    amount: str, code: str | None
) -> None:
    """이번 실행 B7: EXPLAIN 첫 답이 금지 표현으로 걸리고 재시도 답이 이 요청의 planContext.display에만 있는 금액
    (AVG.afterTaxAsset '약 1,712만원')을 인용하면 재시도 본문이 EXPLANATION·calcRunId 540으로 나간다. 같은 답에서
    금액만 근거 없는 값으로 바꾸면 NUMBER_MISMATCH다. 모델 2번"""
    retry_body = f"평균 시나리오면 세후 {amount}이 모여요."
    claude = FakeClaude([text_resp("EXPLANATION", RECOMMEND), text_resp("EXPLANATION", retry_body)])
    result = await outcome(make_agent(claude), explain_request())
    if code is None:
        assert isinstance(result, ChatResponse), result
        assert result.message_type == "EXPLANATION"
        assert result.calc_run_id == 540
        assert result.content == retry_body
    else:
        assert isinstance(result, AgentError), result
        assert result.code == code
    assert len(claude.calls) == 2


async def test_ac7_overlapping_retry_cannot_use_another_requests_calc_display_as_evidence() -> None:
    """이번 실행 B8: 계산하지 않은 요청 A의 재시도 호출을 보류한 동안 같은 agent의 요청 B가 계산(fixture calcRunId 552)을
    하고 그 표시값 '약 2,501만원'을 인용해 정상 RECALC_RESULT로 끝난다. 이어 도착한 A의 재시도 답이 같은 금액을 인용하면
    A는 NUMBER_MISMATCH다(숫자 근거는 요청별이고 다른 요청의 계산 표시값은 섞이지 않는다). 계산 1번(B), 요청마다 모델 2번"""
    qa, qb = "ETF가 뭐예요", "월 50만원으로 바꾸면?"
    amount_body = "평균 약 2,501만원이 모여요."
    calc = FixtureCalc()
    claude = GatedClaude(
        {
            qa: [text_resp("ANSWER", RECOMMEND), text_resp("ANSWER", amount_body)],
            qb: [
                tool_resp("recalculate", {**NULLS, "monthly_saving_amount": 500_000}),
                text_resp("ANSWER", f"월 50만원으로 바꾸면 {amount_body}"),
            ],
        },
        gated=qa,
    )
    agent = make_agent(claude, calc)
    task_a = asyncio.create_task(outcome(agent, question(qa)))
    waiter = asyncio.create_task(claude.retry_started.wait())
    await asyncio.wait({task_a, waiter}, timeout=1, return_when=asyncio.FIRST_COMPLETED)
    waiter.cancel()
    assert claude.retry_started.is_set(), f"요청 A가 재시도하지 않았다: {task_a.result() if task_a.done() else None}"
    b = await asyncio.wait_for(outcome(agent, question(qb)), timeout=1)
    claude.release.set()
    a = await asyncio.wait_for(task_a, timeout=1)
    assert isinstance(b, ChatResponse), b
    assert b.message_type == "RECALC_RESULT"
    assert b.calc_run_id == 552
    assert b.content == f"월 50만원으로 바꾸면 {amount_body}"
    assert isinstance(a, AgentError), a
    assert a.code == "NUMBER_MISMATCH"
    assert len(calc.calls) == 1
    assert claude.calls_by == {qa: 2, qb: 2}


# ---- 이번 실행 직전 S4 코드 blocker(B1·B5·B6) 보완 ----

def sdk_message(text: str) -> dict[str, Any]:
    return {
        "id": "msg_test", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
        "content": [{"type": "text", "text": text}], "stop_reason": "end_turn", "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }


async def test_ac4_sdk_debug_request_log_does_not_contain_rejected_first_answer(
    caplog: pytest.LogCaptureFixture
) -> None:
    """S4 B1, D-1: `anthropic` 로거를 DEBUG로 켜고 실제 AsyncAnthropic(HTTP 전송만 인메모리 대역)으로 금지 답 → 깨끗한 답을
    처리해도, SDK가 요청 JSON 전체를 남기는 로그에 거부된 첫 답 전문(원문·이스케이프·ASCII 표지)이 없다. 재시도 요청이
    실제로 나갔고(요청 2건, 두 번째에 tool_choice none) 재시도 답이 정상 ChatResponse로 나간다"""
    caplog.set_level(logging.DEBUG)
    caplog.set_level(logging.DEBUG, logger="anthropic")
    answers = [
        json.dumps({"message_type": "ANSWER", "content": f"{BODY_SENTINEL} {RECOMMEND}", "citation_chunk_ids": []},
                   ensure_ascii=False),
        json.dumps({"message_type": "ANSWER", "content": CLEAN, "citation_chunk_ids": []}, ensure_ascii=False),
    ]
    bodies: list[dict[str, Any]] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        bodies.append(json.loads(request.content))
        return httpx2.Response(200, json=sdk_message(answers.pop(0)))

    async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
        client = anthropic.AsyncAnthropic(
            api_key="test-key", base_url="http://anthropic.test", max_retries=0, http_client=http
        )
        resp = await outcome(make_agent(client), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.content == CLEAN
    assert len(bodies) == 2
    assert bodies[1].get("tool_choice") == {"type": "none"}
    sdk_records = [r for r in caplog.records if r.name.startswith("anthropic")]
    assert sdk_records, "SDK DEBUG 요청 로그가 잡히지 않아 이 테스트가 아무것도 보지 못한다"
    assert not leaked(caplog.text, BODY_SENTINEL), leaked(caplog.text, BODY_SENTINEL)
    assert not leaked(json.dumps(bodies[1], ensure_ascii=False), BODY_SENTINEL)


async def test_ac4_sdk_debug_request_log_has_no_plan_context_or_history_bodies(
    caplog: pytest.LogCaptureFixture
) -> None:
    """직전 S4 impl-1, D-3: `anthropic` 로거를 DEBUG로 켜고 planContext가 있는 EXPLAIN 금지 답 → 깨끗한 재시도 답을
    처리한 뒤,
    그 재시도 답을 history에 담은 QUESTION을 이어 처리해도 SDK 요청 로그에 planContext 표지·거부된 첫 답·재시도 답
    표지가 없다. 모델에 실제로 보낸 요청 본문에는 planContext와 history가 그대로 있다(로그만 지운다)"""
    caplog.set_level(logging.DEBUG)
    caplog.set_level(logging.DEBUG, logger="anthropic")
    plan_mark, history_mark = "PLANCTXMARKQZ", "HISTMARKQZ"
    plan = copy.deepcopy(PLAN_CONTEXT)
    plan["display"]["conditions.savingsRatioPct"] = f"적금 {plan_mark} 구성"
    retry_answer = f"{RETRY_SENTINEL} {CLEAN}"
    answers = [
        json.dumps({"message_type": "EXPLANATION", "content": f"{BODY_SENTINEL} {RECOMMEND}",
                    "citation_chunk_ids": []}, ensure_ascii=False),
        json.dumps({"message_type": "EXPLANATION", "content": retry_answer, "citation_chunk_ids": []},
                   ensure_ascii=False),
        json.dumps({"message_type": "ANSWER", "content": CLEAN, "citation_chunk_ids": []}, ensure_ascii=False),
    ]
    bodies: list[dict[str, Any]] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        bodies.append(json.loads(request.content))
        return httpx2.Response(200, json=sdk_message(answers.pop(0)))

    async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
        client = anthropic.AsyncAnthropic(
            api_key="test-key", base_url="http://anthropic.test", max_retries=0, http_client=http
        )
        agent = make_agent(client)
        first = await outcome(agent, ChatRequest(
            memberId=1, messageId=9, mode="EXPLAIN", calcRunId=540, baselineScenario="AVG",
            planContext=plan,
        ))
        assert isinstance(first, ChatResponse), first
        assert first.content == retry_answer
        follow_up = await outcome(agent, question(
            f"{history_mark} 더 궁금해요",
            history=[
                {"role": "user", "content": "이전 질문"},
                {"role": "assistant", "content": first.content},
            ],
        ))
    assert isinstance(follow_up, ChatResponse), follow_up
    assert len(bodies) == 3
    sent = [json.dumps(b, ensure_ascii=False) for b in bodies]
    assert plan_mark in sent[0], "모델에 보낸 요청에는 planContext가 그대로 있어야 한다"
    assert RETRY_MARK in sent[2], "모델에 보낸 요청에는 history가 그대로 있어야 한다"
    sdk_records = [r for r in caplog.records if r.name.startswith("anthropic")]
    assert sdk_records, "SDK DEBUG 요청 로그가 잡히지 않아 이 테스트가 아무것도 보지 못한다"
    sdk_text = "\n".join(r.getMessage() for r in sdk_records)
    for mark in (plan_mark, history_mark, BODY_SENTINEL, RETRY_SENTINEL):
        assert not leaked(sdk_text, mark), leaked(sdk_text, mark)
    assert not leaked(caplog.text, plan_mark, BODY_SENTINEL, RETRY_SENTINEL), caplog.text


async def test_ac6_refusal_on_retry_goes_through_assemble_with_server_made_input(
    monkeypatch: pytest.MonkeyPatch
) -> None:
    """S4 B5, P-4: 재시도 응답이 refusal이어도 ChatResponse를 직접 만들지 않고 서버가 만든 OUT_OF_SCOPE 입력(모델 본문·인용
    없음)으로 `assemble`을 거쳐 나간다. 마지막 assemble 호출이 그 입력이고, 응답은 문구표 범위 밖 안내다"""
    import app.llm.agent as agent_module

    seen: list[Any] = []
    real = agent_module.assemble

    def spy(turn: Any, final: Any) -> ChatResponse:
        seen.append(final)
        return real(turn, final)

    monkeypatch.setattr(agent_module, "assemble", spy)
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF란", "doc_type": "EDU"}),
        text_resp("ANSWER", f"{BODY_SENTINEL} {RECOMMEND}", citations=[11]),
        SimpleNamespace(stop_reason="refusal", content=[]),
        text_resp("ANSWER", CLEAN),
    ])
    resp = await outcome(make_agent(claude, retriever=FakeRetriever([etf_chunk(11)])), question())
    assert isinstance(resp, ChatResponse), resp
    assert resp.message_type == "OUT_OF_SCOPE"
    assert resp.content == out_of_scope_content()
    assert resp.citation_chunk_ids == []
    assert seen, "재시도 refusal이 assemble을 거치지 않았다"
    last = seen[-1]
    assert last.message_type == "OUT_OF_SCOPE"
    assert last.citation_chunk_ids == []
    assert not leaked(last.content, BODY_SENTINEL), last.content
    assert len(claude.calls) == 3


@pytest.mark.parametrize("raw", [
    json.dumps({"message_type": "ANSWER", "content": BODY_SENTINEL}, ensure_ascii=False),   # 필수 키 누락
    json.dumps({"message_type": "ANSWER", "content": [BODY_SENTINEL], "citation_chunk_ids": []}, ensure_ascii=False),
    f'{{"message_type": "ANSWER", "content": "{BODY_SENTINEL}',   # 중간에서 잘린 JSON(pydantic은 끝 부분을 남긴다)
], ids=["missing_key", "wrong_type", "truncated_json"])
async def test_ac4_first_answer_bad_output_does_not_leak_body(raw: str, caplog: pytest.LogCaptureFixture) -> None:
    """S4 B6: 재시도 없이 끝나는 첫 답이 형식 오류여도 오류 코드(BAD_OUTPUT)·모델 1번은 지금과 같고,
    AgentError 문자열(pydantic 오류의 input_value 등)·로그에 그 원문이 없다"""
    caplog.set_level(logging.DEBUG)
    claude = FakeClaude([raw_text_resp(raw), text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "BAD_OUTPUT"
    assert not leaked(str(result), BODY_SENTINEL), leaked(str(result), BODY_SENTINEL)
    assert not leaked(caplog.text, BODY_SENTINEL), leaked(caplog.text, BODY_SENTINEL)
    assert len(claude.calls) == 1


async def test_ac4_first_answer_number_mismatch_does_not_leak_body(caplog: pytest.LogCaptureFixture) -> None:
    """S4 B6: 첫 답이 근거 없는 금액 하나('999만원')뿐이면 재시도 없이 NUMBER_MISMATCH(모델 1번)이고, 그 본문(=불일치
    토큰)은 AgentError 문자열·로그에 없다"""
    caplog.set_level(logging.DEBUG)
    body = "999만원"
    claude = FakeClaude([text_resp("ANSWER", body), text_resp("ANSWER", CLEAN)])
    result = await outcome(make_agent(claude), question())
    assert isinstance(result, AgentError), result
    assert result.code == "NUMBER_MISMATCH"
    assert not leaked(str(result), body), str(result)
    assert not leaked(caplog.text, body), leaked(caplog.text, body)
    assert len(claude.calls) == 1
