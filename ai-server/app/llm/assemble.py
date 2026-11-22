import re
from dataclasses import dataclass, field
from functools import cache
from typing import Any, Protocol

from app.core.copy import load_copy
from app.llm.errors import AgentError, ForbiddenAnswer
from app.llm.number_check import NumberMismatch, check_numbers
from app.llm.plan import ETF_TAX_RATE_KEY, TAX_RATE_KEY, plan_etf_name, plan_without_etf
from app.llm.prompts import GOAL_AMOUNT_COPY, NO_PLAN_COPY, OUT_OF_SCOPE_TAX_COPY, SYSTEM_PROMPT
from app.llm.tools import FinalAnswer
from app.schemas import ChatMode, ChatRequest, ChatResponse, MessageType

GENERIC_ETF_NAME = "S&P500 추종 ETF"
# 본문에 써도 되는 ETF 표현(#155, CP-06 수정). 계획 종목 정합성 검사와 교정 지시의 대체 예가 이 상수를 읽는다
ALLOWED_ETF_NAMES = ("코스피200 추종 ETF", GENERIC_ETF_NAME, "나스닥 추종 ETF")

# 개별 주식 종목 추천만 금지한다(CF-07, D-028). ETF 추천 표현과 ETF 상품명은 거부·치환하지 않는다.
# 종목명(삼성전자·삼전 등)은 열린 집합이라 규칙으로 가릴 수 없어, 본문에 추천 낱말이 있을 때만 판정 모델에 묻는다.
# 활용형을 따지지 않고 낱말로 거른다. 부정문("추천하지 않아요")도 판정에 넘기면 모델이 가려낸다.
# 프롬프트가 막는 '사라고 권하는 표현'(권합니다·권장해요·사세요·매수·담아 보세요)도 같이 거른다
_RECOMMENDATION_WORDS = re.compile(r"추천|권[유장하해합]|매수|사세요|사라고|담아\s*보세요|담으세요")


# 본문에 display 키 이름(diff.AVG.afterTaxAssetDelta, current.AVG.afterTaxAsset, AVG.gapToGoal 등)이 그대로 나오면
# 사용자에게 내부 이름이 보인다(NFR-USA-01, #57). 키는 값(문자열)만 인용하고 이름은 쓰지 않는다
_DISPLAY_KEY = re.compile(r"\b(?:current|changed|diff|conditions|assumptions)\.[A-Za-z]|\b(?:GOOD|AVG|BAD)\.[A-Za-z]")


# 본문에 내부 식별자(도구 이름·도구 인자·응답 필드·프롬프트 태그·환경 변수·내부 키 헤더)가 나오면 내부 구조가 보인다(#196).
# 비밀값 자체는 모델 입력에 없지만, 이름이 새면 다음 공격의 단서가 되므로 값과 같이 막는다
_INTERNAL_IDENTIFIER = re.compile(
    r"recalculate|search_documents|plan_context|citation_chunk_ids|message_type|etf_codes|doc_type"
    r"|goal_amount|monthly_saving_amount|savings_ratio_pct|savings_rate_input_pct|target_period_months"
    r"|ANTHROPIC_[A-Z_]+|OPENAI_[A-Z_]+|INTERNAL_API_KEY|DATABASE_URL|SPRING_BASE_URL|X-Internal-[A-Za-z-]+"
    r"|SYSTEM_PROMPT|STOCK_JUDGE_PROMPT",
    re.IGNORECASE,
)
# 시스템 프롬프트 문장을 그대로 옮기면 지시문이 새는 것이다. 공백을 뺀 이 길이 이상이 이어서 같으면 옮긴 것으로 본다.
# 조건 다섯 가지 같은 안내 문장은 정상 답에도 나오므로 사용자 안내용 줄은 프롬프트에서 빼고(_USER_FACING),
# 창도 길게(60자) 잡는다
_PROMPT_WINDOW = 60
_USER_FACING = ("대화로 바꿀 수 있는 조건은",)


def _squash(text: str) -> str:
    return re.sub(r"\s+", "", text)


@cache
def _prompt_windows() -> frozenset[str]:
    """서버 고정 문구는 본문에 나가도 되므로 프롬프트에서 뺀 뒤 창을 만든다."""
    prompt = "\n".join(line for line in SYSTEM_PROMPT.split("\n") if not any(k in line for k in _USER_FACING))
    for allowed in (GOAL_AMOUNT_COPY, NO_PLAN_COPY, OUT_OF_SCOPE_TAX_COPY):
        prompt = prompt.replace(allowed, "\n")
    return frozenset(
        piece[i:i + _PROMPT_WINDOW]
        for piece in (_squash(part) for part in prompt.split("\n"))
        for i in range(len(piece) - _PROMPT_WINDOW + 1)
    )


class ForbiddenExpression(Exception):
    """본문에 나가면 안 되는 표현이 있다. 호출자가 AgentError로 바꿔 올린다."""


class StockRecommendationJudge(Protocol):
    async def recommends_individual_stock(self, text: str) -> bool:
        """본문이 개별 주식 종목을 추천하면 True. ETF 추천·상품명 언급·추천하지 않는다는 말은 False."""
        ...


def needs_stock_judgment(content: str) -> bool:
    """판정 모델을 부를 본문인가. 추천 낱말이 없으면 종목 추천일 수 없어 부르지 않는다(응답마다 호출이 늘지 않게)."""
    return _RECOMMENDATION_WORDS.search(content) is not None


async def check_stock_recommendation(content: str, judge: StockRecommendationJudge) -> None:
    # 문장을 지워 고치면 본문이 비거나 어색해지므로 고치지 않고 거부한다(Spring이 FAILED 처리)
    if needs_stock_judgment(content) and await judge.recommends_individual_stock(content):
        raise ForbiddenExpression("stock_recommendation")


# 문장 경계: .·!·? 뒤(공백이 없어도), 줄바꿈. '15.4%'처럼 뒤에 숫자가 이어지는 소수점은 경계가 아니다
_SENTENCE_BREAK = re.compile(r"((?<=[.!?])(?!\d)\s*|\n+)")


def _sentences(content: str) -> list[tuple[str, str]]:
    """(문장, 뒤따르는 구분 공백) 목록. 이어 붙이면 원문이 된다."""
    parts = _SENTENCE_BREAK.split(content)
    return list(zip(parts[0::2], [*parts[1::2], ""], strict=True))


def check_display_keys(content: str) -> None:
    if _DISPLAY_KEY.search(content):
        raise ForbiddenExpression("internal_key")


def check_internal_leak(content: str) -> None:
    """본문에 내부 식별자나 시스템 프롬프트 문장이 그대로 있으면 거부한다(#196)."""
    if _INTERNAL_IDENTIFIER.search(content):
        raise ForbiddenExpression("internal_leak")
    squashed = _squash(content)
    windows = _prompt_windows()
    if any(squashed[i:i + _PROMPT_WINDOW] in windows for i in range(len(squashed) - _PROMPT_WINDOW + 1)):
        raise ForbiddenExpression("internal_leak")


def names_etf(content: str) -> bool:
    """본문에 허용 ETF 표현이 있다. 종목 표시값이 없는 ETF 0% 계획의 검사에 쓴다(#155, P-4).
    ETF 상품명(브랜드)은 허용 정책(#168, CF-07)이라 여기서도 보지 않는다."""
    return any(name in content for name in ALLOWED_ETF_NAMES)


# 계획을 말하는 문장 신호. 이 신호가 있는 문장이 계획 종목 표시값 없이 다른 허용 ETF 표현만 쓰면 종목을 바꿔 말한 것이다
_PLAN_REFERENCE = ("계획", "이 종목", "그 종목", "해당 종목", "내 종목")


def check_plan_etf_claims(content: str, etf_name: str | None) -> None:
    """표시값이 있는 계획에서 계획이나 계획 종목('이 종목' 등)을 말하는 문장이 다른 허용 ETF 표현만 쓰면
    계획 종목을 바꿔 말한 것이라 거부한다. 다른 ETF를 따로 설명하는 문장은 허용한다(#155)."""
    if etf_name is None:
        return
    others = [name for name in ALLOWED_ETF_NAMES if name != etf_name]
    for sentence, _ in _sentences(content):
        if etf_name not in sentence and any(x in sentence for x in _PLAN_REFERENCE) and any(x in sentence for x in others):
            raise ForbiddenExpression("product_name")


MAX_CITATIONS = 5
_CONDITION_COPY_KEYS = (
    "condition.goalAmount",
    "condition.monthlySavingAmount",
    "condition.savingsRatioPct",
    "condition.savingsRateInputPct",
    "condition.targetPeriodMonths",
)


def out_of_scope_content() -> str:
    """범위 밖 안내. 모델 문장 대신 문구표 문장과 대화로 바꿀 수 있는 5개 조건 이름을 잇는다(D-090·D-092)."""
    copy = load_copy()
    names = "·".join(copy.get(key) for key in _CONDITION_COPY_KEYS)
    return f"{copy.get('outOfScope')} 대화로는 {names}을 바꿔 볼 수 있어요."


@dataclass
class Turn:
    """한 요청 동안 도구가 만든 서버 쪽 사실. 모델 출력보다 이것을 믿는다."""

    req: ChatRequest
    calc_run_id: int | None = None
    condition_changes: dict[str, Any] | None = None
    retrieved_ids: list[int] = field(default_factory=list)
    disclosure_orgs: dict[int, str] = field(default_factory=dict)   # 검색된 공시 chunk_id -> 운용사, 검색 순서
    product_filtered: bool = False   # 종목 필터 검색을 했다. 운용사 보충으로 다른 종목 chunk를 더하지 않는다(#183)
    validation_failed: bool = False   # 이번 요청에서 VALIDATION_FAILED를 받았다. 다시 계산하지 않는다
    fixed_copy_key: str | None = None   # 문구표 고정 문장으로 끝내는 거절을 받았다
    goal_changed_note_params: tuple[str, str] | None = None
    changed_already_exceeded: bool = False
    # 이번 요청의 계산 200 응답 display 값과 422 minDisplay·maxDisplay. 숫자 대조의 근거다(요청마다 따로, FR-AI-02)
    number_evidence: list[str] = field(default_factory=list)


def note_pattern(note: str) -> re.Pattern[str]:
    """문구표 단서를 찾는 패턴. 모델이 문장 사이 공백을 줄바꿈 등으로 바꿔 써도 같은 단서로 본다."""
    return re.compile(r"\s+".join(re.escape(token) for token in note.split()))


_NOTE_GAP = "\x00"   # 세금 단서를 빼고 본문을 볼 때 양옆을 끊는 글자. 숫자·소수점이 아니라 인용 경계로 작동한다


def _quotes_rate(content: str, rate: str) -> bool:
    """세율 표시값 전체가 독립된 숫자로 들어 있다. 바로 앞 글자가 숫자(전각 포함)·소수점·쉼표면 다른 숫자의 일부다.
    바로 뒤 글자가 숫자·쉼표여도 그렇고, 소수점은 뒤에 숫자가 이어질 때만 그렇다. 문장 끝 `15.4%.`는 인용이다(#155 P-5)."""
    return re.search(rf"(?<![\d.,\uff0e\uff0c]){re.escape(rate)}(?!\d|[,\uff0c]|[.\uff0e]\d)", content) is not None


def with_tax_note(req: ChatRequest, content: str) -> str:
    """답이 계획의 세율 표시값을 독립된 숫자로 말했으면 계획에 맞는 세금 단서 하나를 끝에 붙인다(D-067, D-073).
    비과세 종목(raw etfGainTaxRatePct가 0)이면 tax.noteEtfTaxFree, 아니면 tax.note다.
    모델이 계획과 다른 단서를 썼으면 지우고 맞는 단서로 바꾼다. 세율을 말하지 않은 답에는 단서를 붙이지 않고,
    계획과 다른 단서(모든 계획의 tax.noteEtfTaxFree, 비과세 계획의 tax.note)는 지우기만 한다(#155 P-2·P-6).
    단서는 문장 사이 공백·줄바꿈이 달라도 같은 단서로 찾는다. 코드가 붙이므로 모델 입력에는 없다."""
    if req.plan_context is None:
        return content
    display = req.plan_context.display
    # 빈 표시값은 어떤 답에도 들어 있으므로 세율 인용으로 보지 않는다
    rates = [rate for k in (TAX_RATE_KEY, ETF_TAX_RATE_KEY) if (rate := display.get(k))]
    copy = load_copy()
    note, note_free = copy.get("tax.note"), copy.get("tax.noteEtfTaxFree")
    tax_free = req.plan_context.result.assumptions.etf_gain_tax_rate_pct == 0
    right, wrong = (note_free, note) if tax_free else (note, note_free)
    right_re, wrong_re = note_pattern(right), note_pattern(wrong)

    def quotes(text: str) -> bool:
        return any(_quotes_rate(text, rate) for rate in rates)

    # 단서 문장 안의 숫자를 세율 인용으로 오해하지 않도록 단서를 뺀 본문으로 판정한다.
    # 단서 자리에 구분자를 둬서 양옆 글자가 하나의 표시값으로 이어지지 않게 한다
    bare = note_pattern(note_free).sub(_NOTE_GAP, note_pattern(note).sub(_NOTE_GAP, content))
    if not quotes(bare):
        drop = [note_pattern(note_free), note_pattern(note)] if tax_free else [note_pattern(note_free)]
        stripped = content
        for pattern in drop:
            stripped = pattern.sub("", stripped)
        if quotes(stripped):
            # 단서를 지우자 양옆이 이어져 세율 표시값이 새로 생기면 한 칸을 띄워 이어지지 않게 한다
            stripped = content
            for pattern in drop:
                stripped = pattern.sub(" ", stripped)
        return content if stripped == content else stripped.rstrip()
    content = wrong_re.sub("", content).rstrip()
    first = right_re.search(content)
    if first is None:
        return f"{content}\n\n{right}"
    # 맞는 단서를 모델이 여러 번 썼으면 첫 번째만 글자 그대로 남긴다
    return f"{content[:first.start()]}{right}{right_re.sub('', content[first.end():])}".rstrip()


def _cover_orgs(turn: Turn, citations: list[int], min_orgs: int = 2) -> list[int]:
    """공시 인용이 한 운용사뿐이면 이번 검색 결과에서 가장 앞선 다른 운용사 chunk 하나를 더한다(FR-AI-03).
    검색 결과에 다른 운용사가 없으면 그대로 둔다."""
    if turn.product_filtered:
        return citations
    orgs = turn.disclosure_orgs
    cited = [i for i in citations if i in orgs]
    cited_orgs = {orgs[i] for i in cited}
    if not cited or len(cited_orgs) >= min_orgs:
        return citations
    extra = next((i for i, org in orgs.items() if org not in cited_orgs), None)
    if extra is None:
        return citations
    if len(citations) >= MAX_CITATIONS:
        # 상한이면 마지막 인용을 뺀다. 단 그게 유일한 공시 인용이면 그 앞의 것을 뺀다
        drop = len(citations) - (2 if citations[-1] == cited[0] else 1)
        citations = [c for n, c in enumerate(citations) if n != drop]
    return [*citations, extra]


def _number_evidence(turn: Turn) -> list[str]:
    """본문 숫자의 근거: 이번 요청의 planContext display, 계산 응답·422 표시값, 이번 요청 text(U-1, U-2).
    history·검색 결과·이전 요청의 값은 근거가 아니다."""
    req = turn.req
    plan_display = [v for v in req.plan_context.display.values() if isinstance(v, str)] if req.plan_context else []
    return [*plan_display, *turn.number_evidence, *([req.text] if req.text else [])]


def _check_numbers(turn: Turn, body: str) -> None:
    try:
        check_numbers(body, _number_evidence(turn))
    except NumberMismatch:
        raise AgentError("NUMBER_MISMATCH") from None   # 불일치 토큰이 본문 전체일 수 있어 detail을 뺀다(P-7)


def _mask_notes(content: str) -> str:
    """세금 단서 자리를 구분자로 바꾼다. 단서 문장의 글자는 보지 않고 양옆이 이어지지도 않는다."""
    copy = load_copy()
    for key in ("tax.noteEtfTaxFree", "tax.note"):
        content = note_pattern(copy.get(key)).sub(_NOTE_GAP, content)
    return content


def _checked_body(req: ChatRequest, content: str) -> str:
    """계획 종목 표기 검사(#155). ETF 상품명은 허용(CF-07)이라 바꾸지 않는다. 개별 종목 추천은 이미 run·_retry가
    assemble 앞에서 거른다(_check_body). 거부할 표현이면 _ForbiddenAnswer를 올린다."""
    try:
        check_display_keys(content)
        check_internal_leak(content)
        if plan_without_etf(req):
            # ETF 0% 계획의 종목 언급은 재시도도 문장 삭제도 없이 기존 거부로 끝낸다(#155, P-4)
            if names_etf(content):
                raise ForbiddenAnswer("product_name", retry=False)
        else:
            check_plan_etf_claims(content, plan_etf_name(req))   # 계획 종목을 다른 ETF로 바꿔 말하면 거부(#155)
    except ForbiddenExpression as e:
        raise ForbiddenAnswer(str(e)) from e
    return content


# 목표 종류·보유 자산 변경 안내 문구의 마지막 문장. 모델이 문구 앞에 문장을 덧붙이거나 바꿔도 이 문장이 있으면 그 의도다.
# 문장을 조금 바꿔 쓴 경우("보유 자산 변경은 입력 수정에서 하실 수 있어요")도 보유 자산과 입력 수정이 함께 나오면 같은 의도다
_UNCHANGEABLE_ANCHOR = "보유 자산은 입력 수정에서 바꿀 수 있어요"


def _is_unchangeable_answer(content: str) -> bool:
    return _UNCHANGEABLE_ANCHOR in content or ("보유 자산" in content and "입력 수정" in content)


def _fixed_copy(req: ChatRequest, content: str) -> str:
    """모델이 고정 문구를 덧붙이거나 바꿔 말했으면 문구표 문장으로 되돌린다(#190).
    실제 LLM 점검에서 모델이 "목표 금액·종목…은 대화로 바꿀 수 없어요"를 덧붙였는데 목표 금액은 대화로 바꿀 수 있다(D-090).
    범위 밖 세제 문구를 서버가 그대로 두는 것과 같은 방식이다."""
    copy = load_copy()
    if _is_unchangeable_answer(content):
        return copy.get("chat.unchangeableCondition")
    no_plan = copy.get("chat.noPlan")
    if req.plan_context is None and no_plan in content:
        return no_plan
    return content


def assemble(turn: Turn, final: FinalAnswer) -> ChatResponse:
    """DB CHECK(ck_cm_recalc/explain/out_of_scope)를 만족하도록 모델 출력을 서버 사실로 보정한다."""
    req = turn.req
    allowed = set(turn.retrieved_ids)
    citations = list(dict.fromkeys(i for i in final.citation_chunk_ids if i in allowed))[:MAX_CITATIONS]
    # 범위 밖 답은 근거 자료를 인용하지 않는다(AC-15)
    citations = [] if final.message_type == "OUT_OF_SCOPE" else _cover_orgs(turn, citations)

    message_type: MessageType = final.message_type
    calc_run_id: int | None = None
    changes: dict[str, Any] | None = None

    if turn.validation_failed and message_type != "OUT_OF_SCOPE":
        message_type = "CLARIFY"   # 되묻기는 서버가 정한다. 계산 결과가 없으므로 calcRunId·conditionChanges도 없다
    elif turn.calc_run_id is not None and message_type != "OUT_OF_SCOPE":
        message_type, calc_run_id, changes = "RECALC_RESULT", turn.calc_run_id, turn.condition_changes
    elif req.mode is ChatMode.EXPLAIN and message_type != "OUT_OF_SCOPE":
        message_type, calc_run_id = "EXPLANATION", req.calc_run_id
    elif message_type in ("RECALC_RESULT", "EXPLANATION"):
        message_type = "ANSWER"

    # 범위 밖 안내는 모델 문장이 아니라 문구표 문장이다. ANSWER(목표 금액 안내 등)에는 번지지 않는다
    # 상품별 세제·절세 질문은 전용 문구(outOfScope.tax)를 그대로 둔다(D-073). 그 밖의 범위 밖 답은 기본 안내로 바꾼다
    if message_type == "OUT_OF_SCOPE":
        # ETF 0% 계획은 범위 밖 답이라도 ETF 종목을 언급하면 같은 거부로 끝낸다(#155, P-4)
        if plan_without_etf(req) and names_etf(final.content):
            raise ForbiddenAnswer("product_name", retry=False)
        tax_copy = load_copy().get("outOfScope.tax")
        content = tax_copy if tax_copy in final.content else out_of_scope_content()
    else:
        body = _checked_body(req, _fixed_copy(req, final.content))
        _check_numbers(turn, body)   # PR-04. 종목 추천·계획 종목 검사 뒤에 본문을 대조한다
        # 단서를 지우면 양옆 글자가 이어져 종목명·숫자가 새로 생길 수 있어 보정한 본문을 다시 검사한다(#155).
        # 숫자는 단서 자리를 끊고 본다. 단서 안의 숫자는 본문 숫자가 아니다
        content = _checked_body(req, with_tax_note(req, body))
        _check_numbers(turn, _mask_notes(content))
        if not content.strip():
            raise ForbiddenAnswer("empty_body", retry=False)   # 단서 보정 뒤 본문이 비면 정상 응답이 아니다(#155, P-10)
        copy = load_copy()
        if turn.goal_changed_note_params is not None:
            note = copy.get(
                "chat.goalAmountChangedNote",
                currentGoal=turn.goal_changed_note_params[0],
                changedGoal=turn.goal_changed_note_params[1],
            )
            if note not in content:
                content = f"{content}\n\n{note}"
        if turn.changed_already_exceeded:
            exceeded_note = copy.get("chat.changedAlreadyExceeded")
            if exceeded_note not in content:
                content = f"{content}\n\n{exceeded_note}"
    return ChatResponse(
        message_type=message_type,
        content=content,
        baseline_scenario=req.baseline_scenario,
        calc_run_id=calc_run_id,
        condition_changes=changes,
        citation_chunk_ids=citations,
    )
