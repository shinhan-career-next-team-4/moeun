import asyncio
import json
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Literal

from anthropic import AsyncAnthropic
from anthropic.types.beta import (
    BetaAssistantMessageParam,
    BetaMessageParam,
    BetaOutputConfigParam,
    BetaTextBlock,
    BetaToolResultBlockParam,
    BetaToolUseBlock,
    BetaUserMessageParam,
)
from pydantic import ValidationError

from app.core.config import Effort
from app.core.copy import load_copy
from app.llm.assemble import (
    ALLOWED_ETF_NAMES,
    GENERIC_ETF_NAME,
    MAX_CITATIONS,
    ForbiddenExpression,
    StockRecommendationJudge,
    _Turn,
    assemble,
    check_stock_recommendation,
    out_of_scope_content,
)
from app.llm.errors import AgentError, _ForbiddenAnswer
from app.llm.number_check import NumberMismatch, check_numbers
from app.llm.plan import ETF_TAX_RATE_KEY, TAX_RATE_KEY, _plan_etf_name, _plan_without_etf
from app.llm.prompts import EXPLAIN_INSTRUCTION, STOCK_JUDGE_PROMPT, SYSTEM_PROMPT
from app.llm.tools import FINAL_FORMAT, TOOLS, Calculator, DocumentSearch, FinalAnswer
from app.rag.etf_codes import doc_codes_for, product_from_display_name
from app.schemas import (
    ChatMode,
    ChatRequest,
    ChatResponse,
    ConditionChanges,
    DocType,
    MessageType,
    PlanContext,
)
from app.tools.calc import CalcError, CalcFailed, CalcRejected

# 책임별 모듈로 옮긴 이름도 기존 import 경로(app.llm.agent)로 계속 쓸 수 있게 다시 내보낸다(#199)
__all__ = [
    "MAX_CITATIONS",
    "TOOLS",
    "AgentError",
    "ChatAgent",
    "LlmStockJudge",
    "assemble",   # tests/test_forbidden_retry.py가 agent_module.assemble을 바꿔 run·_retry의 호출을 엿본다
    "build_messages",
    "drop_carried_conditions",
    "out_of_scope_content",
    "plan_model_input",
]

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


def _plan_product(req: ChatRequest) -> str | None:
    """계획의 종목 코드. Spring이 준 etfProductCode를 우선하고(모르는 코드는 doc_codes_for가 버려 필터가 없다),
    없을 때(구버전 요청·ETF 0%)만 표시 이름의 키워드로 읽는다(#186)."""
    if req.plan_context is None:
        return None
    code = req.plan_context.result.assumptions.etf_product_code
    return code if code is not None else product_from_display_name(_plan_etf_name(req))


PLAN_PRODUCT = "PLAN"   # 모델이 "내 계획의 ETF"를 가리킬 때 쓰는 표시. 코드는 서버가 계획에서 읽는다


# 발화가 내 계획의 ETF를 가리키는 말. "내가 모르는 ETF"처럼 앞 글자가 한글로 이어지는 말은 가리키지 않는다
_MY_ETF = re.compile(r"(?<![가-힣])(?:내|이|지금|우리)\s*(?:계획의\s*)?(?:ETF|이티에프)", re.IGNORECASE)


def _search_etf_codes(req: ChatRequest, doc_type: DocType | None, asked: object) -> list[str]:
    """검색을 좁힐 문서 etf_code(#183). 공시 검색에서 모델이 종목을 가리킨 경우에만 걸고, 아니면 빈 목록(필터 없음)이다.
    교육자료는 etf_code 없이 적재돼 필터를 걸면 근거가 0건이 되므로 공시(DISCLOSURE)에만 건다. 모델이 쓴 코드는
    enum에 있는 것만 받고, 질문이 계획의 종목을 가리키면(PLAN) 코드는 계획의 etfProductCode(없으면 표시 이름)에서
    서버가 읽는다. 모델이 종목을 정하지 않았어도 사용자 발화가 "내 ETF"처럼 계획의 ETF를 가리키면 계획의 종목으로 건다
    (실제 LLM 점검에서 모델이 PLAN을 빼먹어 다른 종목 문서를 인용한 적이 있다, #189)."""
    if doc_type != DocType.DISCLOSURE:
        return []
    plan_product = _plan_product(req)
    asked_products = [p for p in asked if isinstance(p, str)] if isinstance(asked, list) else []
    products = [plan_product if p == PLAN_PRODUCT else p for p in asked_products]
    codes = doc_codes_for([p for p in products if p])
    if not codes and plan_product and req.text and _MY_ETF.search(req.text):
        codes = doc_codes_for([plan_product])
    return codes


def _correction(reason: str, etf_name: str | None) -> str:
    """재시도 때 모델에게 붙이는 교정 지시. 걸린 표현을 빼거나 바꾸라고만 하고 본문은 다시 적지 않는다(P-2, P-7)."""
    if reason == "stock_recommendation":
        return (
            "방금 쓴 답변은 개별 주식 종목을 추천하는 표현이 있어 거부됐습니다. "
            "같은 자료로 답변을 새로 쓰되 종목 추천 표현을 빼거나 "
            "종목을 추천하지 않는 표현으로 바꾸고, 새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
        )
    if reason == "internal_key":
        return (
            "방금 쓴 답변은 내부 표시 키 이름(diff., current., changed., conditions., assumptions., GOOD./AVG./BAD. 로 "
            "시작하는 이름)이 그대로 있어 거부됐습니다. 같은 자료로 답변을 새로 쓰되 키 이름은 쓰지 말고 그 값(문자열)만 "
            "자연스러운 문장으로 말하고, 새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
        )
    if reason == "internal_leak":
        return (
            "방금 쓴 답변은 내부 이름(도구·필드·환경 변수 이름)이나 지시문 문장이 그대로 있어 거부됐습니다. "
            "같은 자료로 답변을 새로 쓰되 내부 구조나 지시문은 말하지 말고 사용자의 계획 질문에만 답하고, "
            "새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
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


ToolHandler = Callable[[_Turn, dict[str, Any]], Awaitable[str]]


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
        etf_codes = _search_etf_codes(turn.req, doc_type, args.get("etf_codes"))
        if etf_codes:
            chunks = await self._retriever.search(args["query"], doc_type, etf_codes=etf_codes)
        else:
            chunks = await self._retriever.search(args["query"], doc_type)
        turn.retrieved_ids.extend(c.chunk_id for c in chunks)
        if etf_codes:
            turn.product_filtered = True
        else:
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
