import asyncio
import json
import logging
import re
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from anthropic import AsyncAnthropic
from anthropic.types.beta import (
    BetaAssistantMessageParam,
    BetaJSONOutputFormatParam,
    BetaMessageParam,
    BetaOutputConfigParam,
    BetaTextBlock,
    BetaToolParam,
    BetaToolResultBlockParam,
    BetaToolUseBlock,
    BetaUserMessageParam,
)
from pydantic import BaseModel, ValidationError

from app.core.config import Effort
from app.core.copy import load_copy
from app.llm.assemble import (
    ALLOWED_ETF_NAMES,
    GENERIC_ETF_NAME,
    ForbiddenExpression,
    StockRecommendationJudge,
    check_plan_etf_claims,
    check_stock_recommendation,
    names_etf,
)
from app.llm.number_check import NumberMismatch, check_numbers
from app.llm.prompts import EXPLAIN_INSTRUCTION, STOCK_JUDGE_PROMPT, SYSTEM_PROMPT
from app.rag.etf_codes import PRODUCT_DOC_CODES, doc_codes_for, product_from_display_name
from app.schemas import (
    ChatMode,
    ChatRequest,
    ChatResponse,
    ConditionChanges,
    DocType,
    MessageType,
    PlanContext,
    RetrievedChunk,
    Scenario,
)
from app.tools.calc import CalcError, CalcFailed, CalcRejected

log = logging.getLogger(__name__)


class _RedactRequestBody(logging.Filter):
    """SDK가 DEBUG에서 남기는 "Request options" 레코드에서 messages·system을 뺀다. 요청 JSON 전체에는
    planContext 본문과 이전 답변(history, 재시도로 살아남은 답 포함)이 들어 있어 어떤 로그에도 본문을 남기지 않는
    원칙(P-7, AC-4)을 깬다. 모델에 보내는 요청은 건드리지 않고 로그 레코드만 고친다. 로거 레벨은 그대로다."""

    _DROPPED = ("messages", "system")

    def filter(self, record: logging.LogRecord) -> bool:
        options = record.args
        if isinstance(options, dict) and isinstance(options.get("json_data"), dict):
            body = {k: v for k, v in options["json_data"].items() if k not in self._DROPPED}
            record.args = {**options, "json_data": body}
        return True


logging.getLogger("anthropic._base_client").addFilter(_RedactRequestBody())

FALLBACK_BETA = "server-side-fallback-2026-07-01"
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


class Calculator(Protocol):
    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges) -> dict[str, Any]: ...


class DocumentSearch(Protocol):
    async def search(
        self,
        query: str,
        doc_type: DocType | None = None,
        top_k: int | None = None,
        etf_codes: Sequence[str] | None = None,
    ) -> list[RetrievedChunk]: ...


def _nullable(type_: str, **extra: Any) -> dict[str, Any]:
    return {"type": [type_, "null"], **extra}


_CONDITION_FIELDS = {
    "goal_amount": _nullable("integer", description="목표 금액(원)"),
    "monthly_saving_amount": _nullable("integer", description="월 저축액(원)"),
    "savings_ratio_pct": _nullable("integer", description="적금 비중(%), 나머지는 ETF"),
    "savings_rate_input_pct": _nullable("number", description="적금 연 금리(%)"),
    "target_period_months": _nullable("integer", description="목표 기한(개월)"),
}

TOOLS: list[BetaToolParam] = [
    {
        "name": "recalculate",
        "description": (
            "사용자의 현재 계획에서 일부 조건만 바꿔 Spring 계산 엔진으로 다시 계산한다. "
            "바꿀 수 있는 조건은 목표 금액·월 저축액·적금 비중·적금 금리·기한 다섯 가지이고, "
            "바꾸지 않는 조건은 null로 둔다. "
            "금액은 원 단위 정수, 기한은 개월 수."
        ),
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": _CONDITION_FIELDS,
            "required": list(_CONDITION_FIELDS),
            "additionalProperties": False,
        },
    },
    {
        "name": "search_documents",
        "description": "미리 모아 둔 금융 교육자료(EDU)와 운용사 공시(DISCLOSURE)에서 관련 내용을 찾는다.",
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "찾을 내용(한국어 문장)"},
                "doc_type": {"type": "string", "enum": ["EDU", "DISCLOSURE", "ANY"]},
                "etf_codes": _nullable(
                    "array",
                    items={"type": "string", "enum": list(PRODUCT_DOC_CODES)},
                    description="질문이 특정 ETF를 가리킬 때만 그 종목 코드. 종목이 없는 일반 질문이면 null",
                ),
            },
            "required": ["query", "doc_type", "etf_codes"],
            "additionalProperties": False,
        },
    },
]


class FinalAnswer(BaseModel):
    message_type: MessageType
    content: str
    citation_chunk_ids: list[int]


FINAL_FORMAT: BetaJSONOutputFormatParam = {
    "type": "json_schema",
    "schema": {
        "type": "object",
        "properties": {
            "message_type": {
                "type": "string",
                "enum": ["ANSWER", "EXPLANATION", "RECALC_RESULT", "CLARIFY", "OUT_OF_SCOPE"],
            },
            "content": {"type": "string"},
            "citation_chunk_ids": {"type": "array", "items": {"type": "integer"}},
        },
        "required": ["message_type", "content", "citation_chunk_ids"],
        "additionalProperties": False,
    },
}

EXCLUDED_NOTE = "excludedConditions는 이번 발화에 없어 계산에서 뺐습니다. 답변에서 이 조건의 값을 말하지 마세요."

# Spring이 모델 없이 고정 문장으로 답하라고 거절한 코드 → 문구표 키(D-056)
_FIXED_COPY_KEYS = {
    "FIELD_NOT_CHANGEABLE": "chat.unchangeableCondition",
    "NO_PLAN": "chat.noPlan",
    "ALREADY_EXCEEDED": "chat.alreadyExceeded",
}

_CALCULATED_DISPLAY_KEYS = (
    "goalAmount",
    "calcStartMonth",
    "targetMonth",
    "conditions.heldAssets",
    "conditions.monthlySavingAmount",
    "conditions.targetPeriodMonths",
    "conditions.savingsRatioPct",
    "conditions.savingsRateInputPct",
    "assumptions.taxRatePct",   # D-060
)
_SCENARIO_DISPLAY_KEYS = ("afterTaxAsset", "principal", "afterTaxGain", "gapToGoal")
_EXCEEDED_DISPLAY_KEYS = ("goalAmount", "conditions.heldAssets")
TAX_RATE_KEY = "assumptions.taxRatePct"
ETF_TAX_RATE_KEY = "assumptions.etfGainTaxRatePct"

# 도구 실패로 보고 모델에게 is_error 결과로 돌려줄 예외. 그 밖의 예외는 요청 실패로 올린다
_TOOL_ERRORS = (CalcError, ValidationError, KeyError, ValueError, RuntimeError)


# 이번 발화가 그 조건을 가리키는지 보는 느슨한 표현. 자연어를 해석하거나 계산하지 않고, 표현이 없을 때만 값을 뺀다.
# "개월"의 월, "원래"의 원, "한 달"의 달처럼 다른 조건의 글자가 겹치지 않게 정규식으로 가린다(M-3)
# 한글 수사. 이/일/사/오 한 글자는 이번·일단·사람·오늘 같은 어휘와 겹치므로 십·백·천이 붙은 것만 수사로 보고,
# 한 글자+만(오만, 삼만)은 뒤에 금액 동작이나 단위(원·씩·으로·정도·더·올·늘 등)가 올 때만 금액으로 본다.
# 문장 끝의 "오늘은 이만", "일만 하자"는 금액이 아니다. 앞에 한글이 붙은 것(하십시오)은 제외하고,
# 십·백·천 수사 뒤에는 조사·단위나 한글이 아닌 글자가 와야 한다(이만큼, 백화점, 십분 제외).
# 오류 방향이 둘이다. 십·백·천 수사의 조사 목록을 넓히면 세는 수가 늘어 필터가 꺼지는 쪽(이전 값이 남는 쪽)으로만
# 틀리지만, 한 글자+만을 못 세면 사용자가 말한 금액이 지워지는 쪽으로 틀린다. 그래서 한 글자+만의 뒤 말 목록은
# 금액 동작을 넓게 잡고, 어느 쪽이 맞는지 알 수 없는 입력은 센다(값을 남긴다). "천만에요"류 관용구만 뺀다
_KO_DIGIT = "[일이삼사오육칠팔구]"
_KO_FOLLOW = (
    r"(?=원|으로|로|이|가|은|는|를|을|씩|짜리|퍼|프로|개월|년|달|도|면|에(?!요)|쯤|까지|부터|정도|나|밖에|마다|과|와|보다"
    r"|(?![가-힣]))"
)
_KO_ONE_DIGIT_MAN_FOLLOW = r"(?=\s?(?:원|씩|으로|로|정도|쯤|이나|더|덜|올|늘|줄|넣|빼|추가))"
_KO_IDIOM = r"(?!천만에\s*(?:요|그|아니|별|무슨|괜찮|말씀|뭘))"
_KO_TENS = rf"(?:{_KO_DIGIT}?[십백천])+{_KO_DIGIT}?"
_KO_MAN = (
    rf"(?<![가-힣]){_KO_IDIOM}(?:{_KO_TENS}만{_KO_FOLLOW}|{_KO_DIGIT}만{_KO_ONE_DIGIT_MAN_FOLLOW})"
)
_KO_NUMERAL = rf"{_KO_MAN}|(?<![가-힣]){_KO_TENS}{_KO_FOLLOW}"
_GOAL = re.compile(r"목표(?!\s*(?:기간|기한|일|까지))|모으려|모을")
_HELD = re.compile(r"보유|가진|모아\s?둔|모았|이미|지금\s*있는")
_MONTHLY_EXPLICIT = re.compile(r"(?<!개)월|매달|한\s?달|저축(?!\s*기간)|납입|적립")
_MONTHLY = re.compile(rf"{_MONTHLY_EXPLICIT.pattern}|\d\s*만|{_KO_MAN}|만원|\d\s*원")
_PERIOD = re.compile(r"기간|기한|개월|년|\d\s*달")
# 적금·예금 바로 뒤에 금리 단어가 오면 상품 이름이 아니라 금리를 꾸미는 말이다
_RATIO_WORDS = re.compile(r"비중|비율|반반|etf|주식|배분|(?:예금|적금)(?!\s*(?:금리|이율|이자))")
# '줄이자'·'높이자'의 -이자는 제외한다. 이자율, 앞에 한글이 없는 이자, 적금이자·예금이자만 금리로 본다
_RATE_WORDS = re.compile(r"금리|이율|이자율|(?<![가-힣])이자|(?<=금)이자")
_PERCENT = re.compile(r"%|퍼센트|프로|퍼")
_NUMBER = re.compile(rf"\d+(?:\.\d+)?|{_KO_NUMERAL}")
_THOUSANDS_COMMA = re.compile(r"(?<=\d),(?=\d{3})")
# "3년 6개월", "5년(60개월)", "60개월(5년)"은 기간 하나를 두 단위로 말한 것이다
_PERIOD_COMPOUND = re.compile(r"\d+\s*년\s*\(?\s*\d+\s*개월\s*\)?|\d+\s*개월\s*\(?\s*\d+\s*년\s*\)?")
_KO_DIGIT_VALUES = dict(zip("일이삼사오육칠팔구", range(1, 10), strict=True))
_KO_UNIT_VALUES = {"십": 10, "백": 100, "천": 1000}
_AMOUNT_EXPR = re.compile(
    rf"({_NUMBER.pattern})\s*(?:억\s*(?:({_NUMBER.pattern})\s*만\s*원?)?|만\s*원?|원)?"
)


def _parse_ko_numeral(token: str) -> float | None:
    try:
        return float(token)
    except ValueError:
        pass
    if token.endswith("만"):
        base = _parse_ko_numeral(token[:-1])
        return None if base is None else base * 10_000
    total = 0
    current = 0
    for ch in token:
        if ch in _KO_DIGIT_VALUES:
            current = _KO_DIGIT_VALUES[ch]
        elif ch in _KO_UNIT_VALUES:
            total += (current or 1) * _KO_UNIT_VALUES[ch]
            current = 0
        else:
            return None
    return float(total + current)


def _candidate_krw_amounts(text: str) -> set[int]:
    """발화의 숫자·단위 표현에서 나올 수 있는 원 단위 금액 후보 집합(기존 _NUMBER·_THOUSANDS_COMMA 재사용)."""
    cleaned = _THOUSANDS_COMMA.sub("", text)
    candidates: set[int] = set()
    for match in _AMOUNT_EXPR.finditer(cleaned):
        full = match.group(0)
        n1 = _parse_ko_numeral(match.group(1))
        if n1 is None:
            continue
        if "억" in full:
            total = round(n1 * 100_000_000)
            if match.group(2):
                n2 = _parse_ko_numeral(match.group(2))
                if n2 is not None:
                    total += round(n2 * 10_000)
            candidates.add(total)
        elif "만" in full:
            candidates.add(round(n1 if match.group(1).endswith("만") else n1 * 10_000))
        else:
            candidates.add(round(n1))
    return candidates


def check_amount_units(text: str, changes: ConditionChanges) -> bool:
    """1단계 goal_amount 추출 값이 발화의 숫자·단위 표현과 맞는지 대조한다(CF12-9-3)."""
    if changes.goal_amount is None:
        return True
    lowered = text.lower()
    if _count_numbers(lowered) == 0:
        return True
    return changes.goal_amount in _candidate_krw_amounts(lowered)


def _count_numbers(text: str) -> int:
    """발화 속 숫자 덩어리 수. 쉼표 금액(500,000)과 한 기간의 복수 단위(3년 6개월)는 하나로 센다."""
    return len(_NUMBER.findall(_PERIOD_COMPOUND.sub("0년", _THOUSANDS_COMMA.sub("", text))))


def _stated_conditions(text: str) -> set[str]:
    """발화가 가리키는 조건. %는 금리·비중 단어가 있으면 그쪽에만 붙이고, 둘 다 없으면 둘 모두로 본다."""
    ratio_words = bool(_RATIO_WORDS.search(text))
    rate_words = bool(_RATE_WORDS.search(text))
    percent = bool(_PERCENT.search(text))
    goal_hit = bool(_GOAL.search(text))
    held_hit = bool(_HELD.search(text))
    monthly_hit = bool(_MONTHLY_EXPLICIT.search(text)) if (goal_hit or held_hit) else bool(_MONTHLY.search(text))
    stated = {
        "goal_amount": goal_hit,
        "held_assets": held_hit,
        "monthly_saving_amount": monthly_hit,
        "savings_ratio_pct": ratio_words or (not rate_words and percent),
        "savings_rate_input_pct": rate_words or (not ratio_words and percent),
        "target_period_months": bool(_PERIOD.search(text)),
    }
    return {name for name, hit in stated.items() if hit}


def drop_carried_conditions(text: str, changes: ConditionChanges) -> ConditionChanges:
    """history에서 이어받은 조건을 도구 인자에서 뺀다. 비교 기준은 항상 내 계획이다(FR-AI-06, D-041).

    사용자가 말한 값을 지우는 쪽이 이어받은 값이 남는 쪽보다 나쁘므로, 모호하면 남긴다. 발화의 숫자가
    인식한 조건보다 많으면("50으로 하고 기간은 3년") 단위를 못 알아본 값이 있다고 보고 그대로 둔다.
    쉼표 금액과 한 기간의 복수 단위는 숫자 하나로 센다.
    표현도 숫자도 없으면 말한 값이 없으므로 전부 뺀다.
    """
    lowered = text.lower()
    stated = _stated_conditions(lowered)
    if _count_numbers(lowered) > len(stated):
        return changes
    return ConditionChanges.model_validate({k: v for k, v in changes.changed().items() if k in stated})


class AgentError(Exception):
    """설명 생성 실패. Spring은 chat_message를 FAILED로 바꾸고 실패 안내를 보여준다(FR-AI-04)."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(f"{code}: {detail}")
        self.code = code


class _ForbiddenAnswer(AgentError):
    """본문 검사(M-4)에 걸린 답. run이 사유로 재시도 여부를 정한다. 최종 거부 코드는 FORBIDDEN_EXPRESSION이다."""

    def __init__(self, reason: str, *, retry: bool = True):
        super().__init__("FORBIDDEN_EXPRESSION", reason)
        self.reason = reason
        self.retry = retry   # 재시도해도 고칠 수 없는 답이면 False


ETF_NAME_KEY = "assumptions.etfProductName"


def _plan_etf_name(req: ChatRequest) -> str | None:
    """계획이 쓴 종목의 표시값. 없으면 None이고 일반 표현은 'S&P500 추종 ETF'다(#155, CP-06)."""
    return (req.plan_context.display.get(ETF_NAME_KEY) or None) if req.plan_context else None


def _search_etf_codes(req: ChatRequest, asked: object) -> list[str]:
    """검색을 좁힐 문서 etf_code. 질문에서 모델이 정한 종목이 계획의 종목보다 우선이고(검증한 코드만),
    없으면 계획의 종목, 둘 다 없으면 빈 목록(필터 없음)이다(#183)."""
    products = [p for p in asked if isinstance(p, str)] if isinstance(asked, list) else []
    codes = doc_codes_for(products)
    if codes:
        return codes
    return doc_codes_for(list(filter(None, [product_from_display_name(_plan_etf_name(req))])))


def _plan_without_etf(req: ChatRequest) -> bool:
    """planContext는 있지만 종목 표시값이 없다(ETF 0%). planContext가 없는 일반 질문과 구분한다(#155, P-3·P-4)."""
    return req.plan_context is not None and _plan_etf_name(req) is None


def _correction(reason: str, etf_name: str | None) -> str:
    """재시도 때 모델에게 붙이는 교정 지시. 걸린 표현을 빼거나 바꾸라고만 하고 본문은 다시 적지 않는다(P-2, P-7)."""
    if reason == "stock_recommendation":
        return (
            "방금 쓴 답변은 개별 주식 종목을 추천하는 표현이 있어 거부됐습니다. "
            "같은 자료로 답변을 새로 쓰되 종목 추천 표현을 빼거나 "
            "종목을 추천하지 않는 표현으로 바꾸고, 새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
        )
    # 계획 종목이 S&P500이 아니면 계획의 종목은 표시값으로, 그 밖의 ETF는 허용 표현으로 쓰게 한다(#155, P-9).
    # 종목 표시값이 없는 ETF 0% 계획은 상품명이 나오면 재시도 없이 거부하므로 이 지시를 쓰지 않는다
    if etf_name not in (None, GENERIC_ETF_NAME):
        others = ", ".join(f"'{name}'" for name in ALLOWED_ETF_NAMES if name != etf_name)
        generic = f"계획의 종목은 '{etf_name}', 그 밖의 ETF는 {others}"
    else:
        generic = f"'{GENERIC_ETF_NAME}'"
    return (
        "방금 쓴 답변은 지수를 알 수 없는 상품명이 있어 거부됐습니다. 같은 자료로 답변을 새로 쓰되 상품명을 빼거나 "
        f"{generic} 같은 일반 표현으로 바꾸고, 새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
    )


# 재시도 응답이 refusal일 때 assemble에 넘기는 서버 입력. 모델 본문·인용이 없다
_OUT_OF_SCOPE_ANSWER = FinalAnswer(message_type="OUT_OF_SCOPE", content="", citation_chunk_ids=[])


@dataclass
class _Turn:
    """한 요청 동안 도구가 만든 서버 쪽 사실. 모델 출력보다 이것을 믿는다."""

    req: ChatRequest
    calc_run_id: int | None = None
    condition_changes: dict[str, Any] | None = None
    retrieved_ids: list[int] = field(default_factory=list)
    disclosure_orgs: dict[int, str] = field(default_factory=dict)   # 검색된 공시 chunk_id -> 운용사, 검색 순서
    validation_failed: bool = False   # 이번 요청에서 VALIDATION_FAILED를 받았다. 다시 계산하지 않는다
    fixed_copy_key: str | None = None   # 문구표 고정 문장으로 끝내는 거절을 받았다
    goal_changed_note_params: tuple[str, str] | None = None
    changed_already_exceeded: bool = False
    # 이번 요청의 계산 200 응답 display 값과 422 minDisplay·maxDisplay. 숫자 대조의 근거다(요청마다 따로, FR-AI-02)
    number_evidence: list[str] = field(default_factory=list)


ToolHandler = Callable[[_Turn, dict[str, Any]], Awaitable[str]]


_JUDGE_TIMEOUT_SECONDS = 8.0


def _escape_tags(text: str) -> str:
    """본문이 </answer>로 태그를 닫고 판정 지시를 끼워 넣지 못하게 꺾쇠를 전각으로 바꾼다."""
    return text.replace("<", "\uff1c").replace(">", "\uff1e")


class LlmStockJudge:
    """본문이 개별 주식 종목을 추천하는지 모델에게 묻는다(CF-07). 정확히 'false'일 때만 통과시키고,
    true·빈 응답·형식 밖 응답은 모두 종목 추천으로 본다(거부 쪽으로 기운다)."""

    def __init__(self, client: AsyncAnthropic, model: str):
        # 판정은 응답마다 직렬로 최대 2번 더해진다. SDK 자동 재전송을 끄고 타임아웃을 짧게 한다
        # (P-10, Spring read-timeout 30초).
        # 테스트 대역은 with_options가 없어 실제 SDK 클라이언트일 때만 사본을 만든다
        self._client = (
            client.with_options(max_retries=0, timeout=_JUDGE_TIMEOUT_SECONDS)
            if isinstance(client, AsyncAnthropic) else client
        )
        self._model = model

    async def recommends_individual_stock(self, text: str) -> bool:
        resp = await self._client.beta.messages.create(
            model=self._model,
            max_tokens=16,
            system=STOCK_JUDGE_PROMPT,
            messages=[BetaUserMessageParam(role="user", content=f"<answer>{_escape_tags(text)}</answer>")],
        )
        verdict = "".join(b.text for b in resp.content if isinstance(b, BetaTextBlock))
        return verdict.strip(" \t\n.\"'`").lower() != "false"


class ChatAgent:
    def __init__(
        self,
        client: AsyncAnthropic,
        calc: Calculator,
        retriever: DocumentSearch,
        *,
        model: str,
        effort: Effort,
        max_rounds: int,
        stock_judge: StockRecommendationJudge | None = None,
    ):
        self._client = client
        self._stock_judge = stock_judge or LlmStockJudge(client, model)
        # 교정 재시도 호출은 SDK 자동 재전송을 끈다. 재전송되면 사실상 세 번째 호출이 된다(P-10, AC-6).
        # 테스트 대역은 with_options가 없어 실제 SDK 클라이언트일 때만 사본을 만든다
        self._retry_client = client.with_options(max_retries=0) if isinstance(client, AsyncAnthropic) else client
        self._calc = calc
        self._retriever = retriever
        self._model = model
        self._output_config: BetaOutputConfigParam = {"effort": effort, "format": FINAL_FORMAT}
        self._max_rounds = max_rounds
        self._tools: dict[str, ToolHandler] = {
            "recalculate": self._recalculate,
            "search_documents": self._search_documents,
        }

    async def run(self, req: ChatRequest) -> ChatResponse:
        match req:
            case ChatRequest(mode=ChatMode.QUESTION, text=None | ""):
                raise AgentError("BAD_REQUEST", "text is required for QUESTION")
            case ChatRequest(mode=ChatMode.EXPLAIN, calc_run_id=None):
                raise AgentError("BAD_REQUEST", "calc_run_id is required for EXPLAIN")

        fixed = check_plan_context(req)
        if fixed is not None:
            return fixed

        turn = _Turn(req)
        messages = build_messages(req)

        for _ in range(self._max_rounds):
            resp = await self._create(messages)

            match resp.stop_reason:
                case "refusal":
                    return _refusal(req)
                case "max_tokens":
                    raise AgentError("MAX_TOKENS")
                case "tool_use":
                    tool_uses = [b for b in resp.content if isinstance(b, BetaToolUseBlock)]
                    messages.append(BetaAssistantMessageParam(role="assistant", content=resp.content))
                    results = await asyncio.gather(*(self._call_tool(turn, b) for b in tool_uses))
                    if turn.fixed_copy_key is not None:   # 모델을 더 부르지 않는다
                        return ChatResponse(
                            message_type="ANSWER",
                            content=load_copy().get(turn.fixed_copy_key),
                            baseline_scenario=req.baseline_scenario,
                        )
                    messages.append(BetaUserMessageParam(role="user", content=results))   # 결과는 한 메시지에 모두
                case _:
                    final = _parse_final(resp)
                    try:
                        await self._check_body(final)
                        return assemble(turn, final)
                    except _ForbiddenAnswer as e:
                        # 금지 표현이면 이미 실행한 도구 결과를 그대로 두고 모델만 한 번 다시 부른다(M-4 후속)
                        if not e.retry:
                            log.warning("forbidden expression: reason=%s attempt=1 message_id=%s", e.reason, req.message_id)
                            raise
                        log.info("forbidden expression: reason=%s attempt=1 message_id=%s", e.reason, req.message_id)
                        return await self._retry(turn, messages, e.reason)

        raise AgentError("TOO_MANY_ROUNDS")

    async def _create(self, messages: list[BetaMessageParam], *, client: Any = None, **extra: Any) -> Any:
        return await (client or self._client).beta.messages.create(
            model=self._model,
            max_tokens=16000,
            system=SYSTEM_PROMPT,
            tools=TOOLS,
            messages=messages,
            output_config=self._output_config,
            cache_control={"type": "ephemeral"},
            betas=[FALLBACK_BETA],
            fallbacks="default",
            **extra,
        )

    async def _check_body(self, final: FinalAnswer) -> None:
        """M-4, CF-07: 개별 주식 종목 추천이면 거부한다. 범위 밖 답은 서버 문구로 바뀌므로 보지 않는다.
        숫자 대조(assemble)보다 먼저 해야 거부 사유가 숫자 불일치에 가려지지 않는다(U-5)."""
        if final.message_type == "OUT_OF_SCOPE":
            return
        try:
            await check_stock_recommendation(final.content, self._stock_judge)
        except ForbiddenExpression as e:
            raise _ForbiddenAnswer(str(e)) from e

    async def _retry(self, turn: _Turn, messages: list[BetaMessageParam], reason: str) -> ChatResponse:
        """교정 지시를 붙여 모델을 한 번만 다시 부른다. 도구는 실행하지 않고 첫 호출과 같은 분기·보정을 거친다(P-4, P-6).
        거부된 첫 답은 요청에 싣지 않는다. SDK DEBUG 로그가 요청 JSON 전체를 남기기 때문이다(P-7, D-1)."""
        req = turn.req
        correction = _correction(reason, _plan_etf_name(req))
        retry_messages = [*messages, BetaUserMessageParam(role="user", content=correction)]
        # tool_choice none은 이 호출에만 준다(요청 지역)
        retry = await self._create(retry_messages, client=self._retry_client, tool_choice={"type": "none"})
        match retry.stop_reason:
            case "refusal":
                return assemble(turn, _OUT_OF_SCOPE_ANSWER)   # 서버가 만든 입력으로 같은 보정을 거친다(P-4)
            case "max_tokens":
                if _answer_text(retry):   # 텍스트가 있으면 잘린 JSON이다. 본문은 detail에 싣지 않는다(P-17, D-2)
                    raise AgentError("BAD_OUTPUT", "max_tokens")
                raise AgentError("MAX_TOKENS")
            case "tool_use":
                raise AgentError("FORBIDDEN_EXPRESSION", "tool_use on retry")   # PD-009. 도구를 실행하지 않는다
        final = _parse_final(retry)
        try:
            await self._check_body(final)
            return assemble(turn, final)
        except _ForbiddenAnswer as e:
            log.warning("forbidden expression: reason=%s attempt=2 message_id=%s", e.reason, req.message_id)
            raise

    async def _call_tool(self, turn: _Turn, block: BetaToolUseBlock) -> BetaToolResultBlockParam:
        handler = self._tools.get(block.name)
        try:
            if handler is None:
                raise KeyError(f"unknown tool {block.name}")
            content = await handler(turn, dict(block.input))
        except CalcRejected as e:
            return self._on_rejected(turn, block, e)
        except CalcFailed as e:
            raise AgentError(e.code, e.detail) from e
        except _TOOL_ERRORS as e:
            log.info("tool %s failed for message %s: %s", block.name, turn.req.message_id, e)
            return {"type": "tool_result", "tool_use_id": block.id, "content": f"Error: {e}", "is_error": True}
        return {"type": "tool_result", "tool_use_id": block.id, "content": content}

    def _on_rejected(self, turn: _Turn, block: BetaToolUseBlock, e: CalcRejected) -> BetaToolResultBlockParam:
        if e.code == "VALIDATION_FAILED":
            turn.validation_failed = True
            turn.number_evidence.extend(
                v for item in e.errors for k in ("minDisplay", "maxDisplay") if isinstance(v := item.get(k), str)
            )
            body = {"code": e.code, "errors": _errors_for_model(e.errors)}
            return {
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": json.dumps(body, ensure_ascii=False),
                "is_error": True,
            }
        key = _FIXED_COPY_KEYS.get(e.code)
        if key is None:
            raise AgentError("CALC_FAILED", f"unexpected rejection {e.code}") from e
        turn.fixed_copy_key = key
        return {"type": "tool_result", "tool_use_id": block.id, "content": f"Error: {e.code}", "is_error": True}

    async def _recalculate(self, turn: _Turn, args: dict[str, Any]) -> str:
        if turn.validation_failed:
            raise CalcError("허용 범위 오류를 이미 받았습니다. 값을 고쳐 다시 계산하지 말고 사용자에게 되물으세요.")
        changes = ConditionChanges.model_validate(args)
        # history가 있을 때만 이어받은 값이 생길 수 있다. 계산 전에 거른다(assemble에서만 거르면
        # calc_run_id는 누적 값으로 계산된 채 condition_changes만 달라진다)
        dropped: list[str] = []
        if turn.req.text and turn.req.history:
            filtered = drop_carried_conditions(turn.req.text, changes)
            dropped = sorted(changes.changed().keys() - filtered.changed().keys())
            if dropped:
                log.info("dropped carried conditions for message %s: %s", turn.req.message_id, dropped)
            changes = filtered
        if not changes.changed():
            raise CalcError("바꿀 조건이 없습니다. 무엇을 바꿀지 사용자에게 되물으세요.")
        if turn.req.text and not check_amount_units(turn.req.text, changes):
            raise CalcError("목표 금액의 단위(원·만원·억원)가 발화와 맞지 않습니다. 정확한 금액을 다시 물으세요.")
        result = await self._calc.recalculate(turn.req.member_id, turn.req.baseline_scenario, changes)
        turn.calc_run_id = result["calcRunId"]
        turn.condition_changes = changes.changed()
        changed_obj = result.get("changed")
        if isinstance(changed_obj, dict):
            turn.changed_already_exceeded = changed_obj.get("status") == "ALREADY_EXCEEDED"
        display = result.get("display")
        if isinstance(display, dict):
            turn.number_evidence.extend(v for v in display.values() if isinstance(v, str))
            if "goal_amount" in turn.condition_changes:
                cur_goal = display.get("conditions.current.goalAmount")
                chg_goal = display.get("conditions.changed.goalAmount")
                if isinstance(cur_goal, str) and isinstance(chg_goal, str):
                    turn.goal_changed_note_params = (cur_goal, chg_goal)
        if dropped:
            # 모델이 뺀 조건을 계산에 쓴 것처럼 답하지 않게 알린다(서버 사실 원칙)
            result = {**result, "excludedConditions": dropped, "note": EXCLUDED_NOTE}
        return json.dumps(result, ensure_ascii=False)

    async def _search_documents(self, turn: _Turn, args: dict[str, Any]) -> str:
        doc_type = None if args["doc_type"] == "ANY" else DocType(args["doc_type"])
        etf_codes = _search_etf_codes(turn.req, args.get("etf_codes"))
        if etf_codes:
            chunks = await self._retriever.search(args["query"], doc_type, etf_codes=etf_codes)
        else:
            chunks = await self._retriever.search(args["query"], doc_type)
        turn.retrieved_ids.extend(c.chunk_id for c in chunks)
        if not etf_codes:
            # 종목을 지정한 검색은 운용사 1곳일 수 있어 운용사 2곳 보충(_cover_orgs) 대상이 아니다(#183)
            turn.disclosure_orgs.update(
                {c.chunk_id: c.org_name for c in chunks if c.doc_type == DocType.DISCLOSURE}
            )
        payload = [c.model_dump(by_alias=False, exclude={"distance"}) for c in chunks]
        return json.dumps(payload, ensure_ascii=False)


def _errors_for_model(errors: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """오류 항목은 그대로 넘긴다. 단 표시 문자열이 빠진 OUT_OF_RANGE는 모델이 원 단위 값을 환산하지 못하게
    min·max를 뺀다(계약 위반 방어, C3-3)."""
    return [
        {k: v for k, v in item.items() if k not in ("min", "max")}
        if item.get("reason") == "OUT_OF_RANGE" and not (item.get("minDisplay") and item.get("maxDisplay"))
        else item
        for item in errors
    ]


def _refusal(req: ChatRequest) -> ChatResponse:
    return ChatResponse(
        message_type="OUT_OF_SCOPE", content=out_of_scope_content(), baseline_scenario=req.baseline_scenario
    )


def _answer_text(resp: Any) -> str:
    return "".join(b.text for b in resp.content if isinstance(b, BetaTextBlock))


def _parse_final(resp: Any) -> FinalAnswer:
    """최종 응답 파싱. 오류 문구에 모델 원문(input_value)이 실리지 않게 오류 종류와 위치만 남긴다(P-16, P-7)."""
    try:
        return FinalAnswer.model_validate_json(_answer_text(resp))
    except ValidationError as e:
        raise AgentError("BAD_OUTPUT", ",".join(f"{err['type']}@{err['loc']}" for err in e.errors())) from None


def _reject(req: ChatRequest, code: str) -> AgentError:
    # planContext 본문은 로그에 남기지 않는다. 오류 코드와 calcRunId만(C1a-8)
    log.warning("plan_context rejected: code=%s calcRunId=%s", code, req.calc_run_id)
    return AgentError(code)


def _missing_display(plan: PlanContext) -> bool:
    display = plan.display
    if plan.result.status == "ALREADY_EXCEEDED":
        return any(k not in display for k in _EXCEEDED_DISPLAY_KEYS)
    required = list(_CALCULATED_DISPLAY_KEYS)
    for item in plan.result.scenarios:
        name = item.scenario.value
        required.extend(f"{name}.{k}" for k in _SCENARIO_DISPLAY_KEYS)
        if item.is_achieved:
            required.append(f"{name}.goalReachMonth")
    return any(k not in display for k in required)


def check_plan_context(req: ChatRequest) -> ChatResponse | None:
    """모델을 부르기 전 planContext 검사(계약 C1a-8 검사 2~5). 고정 문장으로 끝나면 그 응답을 돌려준다."""
    plan = req.plan_context
    if req.mode is ChatMode.EXPLAIN:
        if plan is None or plan.calc_run_id != req.calc_run_id:
            raise _reject(req, "BAD_REQUEST")
        if plan.result.status == "ALREADY_EXCEEDED":
            return ChatResponse(
                message_type="EXPLANATION",
                content=load_copy().get("chat.alreadyExceeded"),
                baseline_scenario=req.baseline_scenario,
                calc_run_id=req.calc_run_id,
            )
    if plan is not None and _missing_display(plan):
        raise _reject(req, "CONTEXT_DISPLAY_MISSING")
    return None


def plan_model_input(plan: PlanContext) -> dict[str, Any]:
    """모델에 넘기는 planContext. raw 금액·식별 정보는 빼고 display 문자열과 문구표 문장만 담는다(C1a-8)."""
    copy = load_copy()
    known = copy.keys()
    assumptions = plan.result.assumptions
    notes: list[str] = []
    dropped = 0
    for code in assumptions.excluded_items or []:
        if f"excluded.{code}" in known:
            notes.append(copy.get(f"excluded.{code}"))
        else:
            dropped += 1
    if dropped:
        log.warning("excludedItems code without copy dropped: count=%s", dropped)
    shown: dict[str, Any] = {"isDefaultSavingsRate": assumptions.is_default_savings_rate, "excludedNotes": notes}
    if assumptions.scenario_method is not None:
        shown["scenarioMethodNote"] = copy.get(f"scenarioMethod.{assumptions.scenario_method}")
    return {
        "result": {
            "status": plan.result.status,
            "scenarios": [{"scenario": s.scenario.value, "isAchieved": s.is_achieved} for s in plan.result.scenarios],
            "assumptions": shown,
        },
        "display": plan.display,
    }


def build_messages(req: ChatRequest) -> list[BetaMessageParam]:
    """기록은 user로 시작하고 역할이 번갈아야 한다. 같은 역할이 이어지면 합친다."""
    plan_input = plan_model_input(req.plan_context) if req.plan_context else None
    context = json.dumps(plan_input, ensure_ascii=False)
    body = req.text if req.mode is ChatMode.QUESTION else EXPLAIN_INSTRUCTION
    current = (
        f"<plan_context>{context}</plan_context>\n"
        f"<baseline_scenario>{req.baseline_scenario.value}</baseline_scenario>\n"
        f"<request>{body}</request>"
    )

    copy = load_copy()
    notes = (copy.get("tax.note"), copy.get("tax.noteEtfTaxFree"))
    turns: list[tuple[Literal["user", "assistant"], str]] = []
    for role, content in [*((h.role, h.content) for h in req.history), ("user", current)]:
        if role == "assistant":
            # 코드가 붙인 세금 단서는 모델 입력에 다시 넣지 않는다(D-067, D-073)
            for note in notes:
                content = _note_pattern(note).sub("", content)
            content = content.strip()
            if not turns or not content:
                continue
        if turns and turns[-1][0] == role:
            turns[-1] = (role, f"{turns[-1][1]}\n\n{content}")
        else:
            turns.append((role, content))
    return [
        BetaUserMessageParam(role="user", content=content)
        if role == "user"
        else BetaAssistantMessageParam(role="assistant", content=content)
        for role, content in turns
    ]


def _note_pattern(note: str) -> re.Pattern[str]:
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
    right_re, wrong_re = _note_pattern(right), _note_pattern(wrong)

    def quotes(text: str) -> bool:
        return any(_quotes_rate(text, rate) for rate in rates)

    # 단서 문장 안의 숫자를 세율 인용으로 오해하지 않도록 단서를 뺀 본문으로 판정한다.
    # 단서 자리에 구분자를 둬서 양옆 글자가 하나의 표시값으로 이어지지 않게 한다
    bare = _note_pattern(note_free).sub(_NOTE_GAP, _note_pattern(note).sub(_NOTE_GAP, content))
    if not quotes(bare):
        drop = [_note_pattern(note_free), _note_pattern(note)] if tax_free else [_note_pattern(note_free)]
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


def _cover_orgs(turn: _Turn, citations: list[int], min_orgs: int = 2) -> list[int]:
    """공시 인용이 한 운용사뿐이면 이번 검색 결과에서 가장 앞선 다른 운용사 chunk 하나를 더한다(FR-AI-03).
    검색 결과에 다른 운용사가 없으면 그대로 둔다."""
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


def _number_evidence(turn: _Turn) -> list[str]:
    """본문 숫자의 근거: 이번 요청의 planContext display, 계산 응답·422 표시값, 이번 요청 text(U-1, U-2).
    history·검색 결과·이전 요청의 값은 근거가 아니다."""
    req = turn.req
    plan_display = [v for v in req.plan_context.display.values() if isinstance(v, str)] if req.plan_context else []
    return [*plan_display, *turn.number_evidence, *([req.text] if req.text else [])]


def _check_numbers(turn: _Turn, body: str) -> None:
    try:
        check_numbers(body, _number_evidence(turn))
    except NumberMismatch:
        raise AgentError("NUMBER_MISMATCH") from None   # 불일치 토큰이 본문 전체일 수 있어 detail을 뺀다(P-7)


def _mask_notes(content: str) -> str:
    """세금 단서 자리를 구분자로 바꾼다. 단서 문장의 글자는 보지 않고 양옆이 이어지지도 않는다."""
    copy = load_copy()
    for key in ("tax.noteEtfTaxFree", "tax.note"):
        content = _note_pattern(copy.get(key)).sub(_NOTE_GAP, content)
    return content


def _checked_body(req: ChatRequest, content: str) -> str:
    """계획 종목 표기 검사(#155). ETF 상품명은 허용(CF-07)이라 바꾸지 않는다. 개별 종목 추천은 이미 run·_retry가
    assemble 앞에서 거른다(_check_body). 거부할 표현이면 _ForbiddenAnswer를 올린다."""
    try:
        if _plan_without_etf(req):
            # ETF 0% 계획의 종목 언급은 재시도도 문장 삭제도 없이 기존 거부로 끝낸다(#155, P-4)
            if names_etf(content):
                raise _ForbiddenAnswer("product_name", retry=False)
        else:
            check_plan_etf_claims(content, _plan_etf_name(req))   # 계획 종목을 다른 ETF로 바꿔 말하면 거부(#155)
    except ForbiddenExpression as e:
        raise _ForbiddenAnswer(str(e)) from e
    return content


def assemble(turn: _Turn, final: FinalAnswer) -> ChatResponse:
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
        if _plan_without_etf(req) and names_etf(final.content):
            raise _ForbiddenAnswer("product_name", retry=False)
        tax_copy = load_copy().get("outOfScope.tax")
        content = tax_copy if tax_copy in final.content else out_of_scope_content()
    else:
        body = _checked_body(req, final.content)
        _check_numbers(turn, body)   # PR-04. 종목 추천·계획 종목 검사 뒤에 본문을 대조한다
        # 단서를 지우면 양옆 글자가 이어져 종목명·숫자가 새로 생길 수 있어 보정한 본문을 다시 검사한다(#155).
        # 숫자는 단서 자리를 끊고 본다. 단서 안의 숫자는 본문 숫자가 아니다
        content = _checked_body(req, with_tax_note(req, body))
        _check_numbers(turn, _mask_notes(content))
        if not content.strip():
            raise _ForbiddenAnswer("empty_body", retry=False)   # 단서 보정 뒤 본문이 비면 정상 응답이 아니다(#155, P-10)
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
