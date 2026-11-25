import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from anthropic import APIError, AsyncAnthropic
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
from app.core.contract import (
    CALC_SIDES,
    CHANGED_SIDE,
    CURRENT_SIDE,
    ETF_CODE,
    ETF_NAME,
    GOAL_AMOUNT,
    SAVINGS_RATIO,
    calc_condition_key,
)
from app.core.copy import load_copy
from app.core.errors import ErrorCode
from app.llm.assemble import (
    ALLOWED_ETF_NAMES,
    GENERIC_ETF_NAME,
    MAX_CITATIONS,
    ForbiddenExpression,
    StockRecommendationJudge,
    Turn,
    assemble,
    check_stock_recommendation,
    out_of_scope_content,
)
from app.llm.conditions import (
    GOAL_TYPE_WORD,
    carried_amount_sources,
    check_amount_units,
    drop_carried_conditions,
    names_goal,
    pointed_source,
    without_goal_name,
)
from app.llm.context import build_messages, check_plan_context, plan_model_input
from app.llm.errors import AgentError, ForbiddenAnswer, ForbiddenReason
from app.llm.etf import search_etf_codes, stored_plan_product
from app.llm.judge import LlmStockJudge
from app.llm.plan import plan_etf_name
from app.llm.prompts import SYSTEM_PROMPT
from app.llm.tools import FINAL_FORMAT, TOOLS, Calculator, DocumentSearch, FinalAnswer
from app.schemas import (
    ChatMode,
    ChatRequest,
    ChatResponse,
    ConditionChanges,
    DocType,
)
from app.tools.calc import CalcError, CalcFailed, CalcRejected, SpringRejectCode

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
# 대화 한 건의 원인 추적 로그(#316). 요청·도구 입출력·모델 답·최종 답을 message_id와 함께 본문까지 남긴다
trace = logging.getLogger("moeun.ai.trace")


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)

FALLBACK_BETA = "server-side-fallback-2026-07-01"
EXCLUDED_NOTE = "excludedConditions는 이번 발화에 없어 계산에서 뺐습니다. 답변에서 이 조건의 값을 말하지 마세요."
# 종목을 바꿨지만 비교의 ETF 비중이 0%라 종목이 결과에 영향이 없을 때 모델에게 붙이는 안내(D-097 합의 4)
ETF_ZERO_SENTENCE = "ETF 비중이 0%라서 종목을 바꿔도 결과는 같아요."
ETF_ZERO_NOTE = (
    f"바꾼 쪽의 ETF 비중이 0%라 종목은 결과에 영향이 없습니다. 종목 소개 없이 '{ETF_ZERO_SENTENCE}' 한 문장으로 답하세요."
)
# 목표 이름만 바꿨을 때 붙이는 안내(#345). 이름은 계산 조건이 아니라 두 쪽 결과가 같다
GOAL_NAME_ONLY_NOTE = (
    "목표 이름만 바꿨고 예상 결과는 그대로입니다. "
    "바뀐 이름과 결과가 그대로라는 것만 한 문장으로 답하고 금액은 말하지 마세요."
)
_GOAL_NAME_NOT_SAID_ERROR = (
    "goal_custom_name은 사용자가 이번에 목표 이름을 바꾸라고 말한 이름만 넣습니다. 이름을 바꾸라는 말이 없으면 "
    "goal_custom_name을 null로 두고, 이름이 분명하지 않으면 어떤 이름으로 바꿀지 되물으세요."
)
_GOAL_TYPE_ERROR = "목표 종류는 대화로 바꾸지 않습니다. 계산하지 말고 정해진 안내 문구로 답하세요."
_ALL_SAVINGS_PCT = 100
_SAME_PRODUCT_ERROR = "이미 계획의 종목입니다. 계산하지 말고 지금 계획이 이미 그 종목이라고만 답하세요."
_NO_CHANGE_ERROR = "바꿀 조건이 없습니다. 무엇을 바꿀지 사용자에게 되물으세요."
# 계산 200을 받은 뒤 빈 인자·같은 인자로 다시 부르면 Spring을 다시 부르지 않는다. "되물으세요"는 받은 결과와 어긋나
# 모델이 계산을 되풀이했다(#317, S8-8d message 1708)
_ALREADY_CALCULATED_ERROR = "이미 이 조건으로 계산했습니다. 다시 계산하지 말고 앞에서 받은 계산 결과로 답하세요."
# 계산하지 않고 빈 본문의 RECALC_RESULT를 내면 한 번 더 부를 때 붙인다. 계산이 필요한 답이라 도구는 그대로 둔다(#317).
# 그 밖의 빈 본문은 재시도 없이 거부한다(#155 P-10)
_EMPTY_RECALC_NOTE = (
    "방금 답의 content가 비어 있습니다. 조건을 바꾸는 요청이면 recalculate를 부르고, "
    "아니면 같은 JSON 형식으로 content를 채워 답하세요."
)

# Spring이 모델 없이 고정 문장으로 답하라고 거절한 코드 → 문구표 키(D-056)
_FIXED_COPY_KEYS: dict[str, str] = {
    SpringRejectCode.FIELD_NOT_CHANGEABLE: "chat.unchangeableCondition",
    SpringRejectCode.NO_PLAN: "chat.noPlan",
    SpringRejectCode.ALREADY_EXCEEDED: "chat.alreadyExceeded",
}

# 도구 실패로 보고 모델에게 is_error 결과로 돌려줄 예외. 그 밖의 예외는 요청 실패로 올린다
_TOOL_ERRORS = (CalcError, ValidationError, KeyError, ValueError, RuntimeError)


def _correction(reason: ForbiddenReason, etf_name: str | None) -> str:
    """재시도 때 모델에게 붙이는 교정 지시. 걸린 표현을 빼거나 바꾸라고만 하고 본문은 다시 적지 않는다(P-2)."""
    if reason == ForbiddenReason.STOCK_RECOMMENDATION:
        return (
            "방금 쓴 답변은 개별 주식 종목을 추천하는 표현이 있어 거부됐습니다. "
            "같은 자료로 답변을 새로 쓰되 종목 추천 표현을 빼거나 "
            "종목을 추천하지 않는 표현으로 바꾸고, 새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
        )
    if reason == ForbiddenReason.INTERNAL_KEY:
        return (
            "방금 쓴 답변은 내부 표시 키 이름(diff., current., changed., conditions., assumptions., GOOD./AVG./BAD. 로 "
            "시작하는 이름)이 그대로 있어 거부됐습니다. 같은 자료로 답변을 새로 쓰되 키 이름은 쓰지 말고 그 값(문자열)만 "
            "자연스러운 문장으로 말하고, 새 도구 호출 없이 같은 JSON 형식으로 답변하세요."
        )
    if reason == ForbiddenReason.INTERNAL_LEAK:
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


ToolHandler = Callable[[Turn, dict[str, Any]], Awaitable[str]]


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
        trace.info("chat request: message_id=%s body=%s", req.message_id, req.model_dump_json(by_alias=True))
        try:
            resp = await self._run(req)
        except AgentError as e:
            trace.warning("chat failed: message_id=%s code=%s detail=%s", req.message_id, e.code, e.detail)
            raise
        except APIError as e:   # 모델·판정 모델 호출 실패도 이 메시지의 실패로 남긴다
            trace.warning("chat failed: message_id=%s llm=%s detail=%s", req.message_id, type(e).__name__, e)
            raise
        trace.info("chat answer: message_id=%s body=%s", req.message_id, resp.model_dump_json(by_alias=True))
        return resp

    async def _run(self, req: ChatRequest) -> ChatResponse:
        match req:
            case ChatRequest(mode=ChatMode.QUESTION, text=None | ""):
                raise AgentError(ErrorCode.BAD_REQUEST, "text is required for QUESTION")
            case ChatRequest(mode=ChatMode.EXPLAIN, calc_run_id=None):
                raise AgentError(ErrorCode.BAD_REQUEST, "calc_run_id is required for EXPLAIN")

        fixed = check_plan_context(req)
        if fixed is not None:
            return fixed

        turn = Turn(req)
        # 가리키는 답("그거요")에 다시 되물으며 앞 발화의 금액을 인용해도 지어낸 숫자가 아니다(#270)
        if req.text and (source := pointed_source(req.text, req.history)) is not None:
            turn.number_evidence.append(source)
        messages = build_messages(req)
        called: list[str] = []   # 도구 호출 순서. 한도를 넘으면 실패 상세로 남긴다
        empty_recalc_retried = False

        for round_no in range(self._max_rounds):
            last_round = round_no == self._max_rounds - 1
            # 계산 결과가 있으면 마지막 라운드는 도구 없이 그 결과로 답하게 한다(#317). 결과가 없으면 막지 않는다
            extra: dict[str, Any] = {"tool_choice": {"type": "none"}} if last_round and turn.calc_run_id else {}
            resp = await self._create(messages, **extra)
            _trace_model_answer(req, resp, attempt=1)

            match resp.stop_reason:
                case "refusal":
                    return _refusal(req)
                case "max_tokens":
                    raise AgentError(ErrorCode.MAX_TOKENS)
                case "tool_use":
                    tool_uses = [b for b in resp.content if isinstance(b, BetaToolUseBlock)]
                    called.extend(b.name for b in tool_uses)
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
                    if _empty_recalc_without_calc(turn, final) and not last_round and not empty_recalc_retried:
                        empty_recalc_retried = True
                        trace.info("empty recalc answer: message_id=%s round=%s", req.message_id, round_no + 1)
                        messages.append(BetaAssistantMessageParam(role="assistant", content=resp.content))
                        messages.append(BetaUserMessageParam(role="user", content=_EMPTY_RECALC_NOTE))
                        continue
                    try:
                        await self._check_body(final)
                        return assemble(turn, final)
                    except ForbiddenAnswer as e:
                        # 금지 표현이면 이미 실행한 도구 결과를 그대로 두고 모델만 한 번 다시 부른다(M-4 후속)
                        if not e.retry:
                            log.warning("forbidden expression: reason=%s attempt=1 message_id=%s", e.reason, req.message_id)
                            raise
                        log.info("forbidden expression: reason=%s attempt=1 message_id=%s", e.reason, req.message_id)
                        return await self._retry(turn, messages, e.reason)

        raise AgentError(ErrorCode.TOO_MANY_ROUNDS, ",".join(called))

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
            raise ForbiddenAnswer(ForbiddenReason(str(e))) from e

    async def _retry(self, turn: Turn, messages: list[BetaMessageParam], reason: ForbiddenReason) -> ChatResponse:
        """교정 지시를 붙여 모델을 한 번만 다시 부른다. 도구는 실행하지 않고 첫 호출과 같은 분기·보정을 거친다(P-4, P-6).
        거부된 첫 답은 요청에 싣지 않는다. 모델이 그 답을 고쳐 쓰지 않고 새로 쓰게 하려는 것이다(D-1)."""
        req = turn.req
        correction = _correction(reason, plan_etf_name(req))
        retry_messages = [*messages, BetaUserMessageParam(role="user", content=correction)]
        # tool_choice none은 이 호출에만 준다(요청 지역)
        retry = await self._create(retry_messages, client=self._retry_client, tool_choice={"type": "none"})
        _trace_model_answer(req, retry, attempt=2)
        match retry.stop_reason:
            case "refusal":
                return assemble(turn, _OUT_OF_SCOPE_ANSWER)   # 서버가 만든 입력으로 같은 보정을 거친다(P-4)
            case "max_tokens":
                if _answer_text(retry):   # 텍스트가 있으면 잘린 JSON이다. 원문은 model answer 로그에 있다(P-17, D-2)
                    raise AgentError(ErrorCode.BAD_OUTPUT, "max_tokens")
                raise AgentError(ErrorCode.MAX_TOKENS)
            case "tool_use":
                raise AgentError(ErrorCode.FORBIDDEN_EXPRESSION, "tool_use on retry")   # PD-009. 도구를 실행하지 않는다
        final = _parse_final(retry)
        try:
            await self._check_body(final)
            return assemble(turn, final)
        except ForbiddenAnswer as e:
            log.warning("forbidden expression: reason=%s attempt=2 message_id=%s", e.reason, req.message_id)
            raise

    async def _call_tool(self, turn: Turn, block: BetaToolUseBlock) -> BetaToolResultBlockParam:
        handler = self._tools.get(block.name)
        try:
            if handler is None:
                raise KeyError(f"unknown tool {block.name}")
            content = await handler(turn, dict(block.input))
        except CalcRejected as e:
            result = self._on_rejected(turn, block, e)
        except CalcFailed as e:
            _trace_tool(turn, block, f"CalcFailed {e.code}: {e.detail}")
            raise AgentError(e.code, e.detail) from e
        except _TOOL_ERRORS as e:
            log.info("tool %s failed for message %s: %s", block.name, turn.req.message_id, e)
            result = {"type": "tool_result", "tool_use_id": block.id, "content": f"Error: {e}", "is_error": True}
        else:
            result = {"type": "tool_result", "tool_use_id": block.id, "content": content}
        _trace_tool(turn, block, result.get("content"))
        return result

    def _on_rejected(self, turn: Turn, block: BetaToolUseBlock, e: CalcRejected) -> BetaToolResultBlockParam:
        if e.code == SpringRejectCode.VALIDATION_FAILED:
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
            raise AgentError(ErrorCode.CALC_FAILED, f"unexpected rejection {e.code}") from e
        turn.fixed_copy_key = key
        return {"type": "tool_result", "tool_use_id": block.id, "content": f"Error: {e.code}", "is_error": True}

    async def _recalculate(self, turn: Turn, args: dict[str, Any]) -> str:
        if turn.validation_failed:
            raise CalcError("허용 범위 오류를 이미 받았습니다. 값을 고쳐 다시 계산하지 말고 사용자에게 되물으세요.")
        changes = ConditionChanges.model_validate(args)
        # 모델이 인자를 하나라도 넣었는지. 거르기 전 값으로 같은 계산 반복을 판단한다(#317)
        requested = changes.changed()
        said = turn.req.text or ""
        name = changes.goal_custom_name.strip() if changes.goal_custom_name is not None else None
        if name is not None:
            # 이름은 사용자가 이번에 바꾸라고 말한 것만 받는다(#345). 이름 속 숫자·낱말은 다른 조건으로 읽지 않는다
            if GOAL_TYPE_WORD in said:
                raise CalcError(_GOAL_TYPE_ERROR)
            if not names_goal(said, name):
                raise CalcError(_GOAL_NAME_NOT_SAID_ERROR)
            said = without_goal_name(said, name)
            changes = changes.model_copy(update={"goal_custom_name": None})
        # history가 있으면 이어받은 값이 생길 수 있다. 계산 전에 거른다(assemble에서만 거르면
        # calc_run_id는 누적 값으로 계산된 채 condition_changes만 달라진다). 이름을 바꾸면 이름을 뺀 발화로 거른다
        dropped: list[str] = []
        if said and (turn.req.history or name is not None):
            filtered = drop_carried_conditions(said, changes, turn.req.history)
            dropped = sorted(changes.changed().keys() - filtered.changed().keys())
            if dropped:
                log.info("dropped carried conditions for message %s: %s", turn.req.message_id, dropped)
            changes = filtered
        if name is not None:
            changes = changes.model_copy(update={"goal_custom_name": name})
        # 계획에 저장된 종목(ETF 0%여도 있음, #297). 모르면(#297 전 Spring) 가정하지 않고 키를 Spring에 넘긴다.
        # Spring은 계획과 같은 종목이면 바꾸지 않은 것으로 본다(internal-calc C2-2, #294)
        planned = stored_plan_product(turn.req)
        if changes.etf_product_code is not None and changes.etf_product_code == planned:
            # 계획과 같은 종목은 바꾸지 않은 것이다(D-097)
            changes = changes.model_copy(update={"etf_product_code": None})
            if not changes.changed():
                raise CalcError(_SAME_PRODUCT_ERROR)
        # 모델이 빈 인자로 불렀거나 이미 보낸 조건이면 다시 계산하지 않는다. 걸러서 빈 것은 아래 되묻기 오류다
        if turn.calculated and (not requested or changes.changed() in turn.calculated):
            raise CalcError(_ALREADY_CALCULATED_ERROR)
        if not changes.changed():
            raise CalcError(_NO_CHANGE_ERROR)
        if said and not check_amount_units(said, changes):
            raise CalcError("목표 금액의 단위(원·만원·억원)가 발화와 맞지 않습니다. 정확한 금액을 다시 물으세요.")
        # 질문을 접수한 때의 계획 버전이 있으면 그 기준으로 비교한다(#396). 없으면 지금까지처럼 지금 계획 기준이다
        base_id = turn.req.base_plan_version_id
        base_kwargs = {} if base_id is None else {"base_plan_version_id": base_id}
        result = await self._calc.recalculate(turn.req.member_id, turn.req.baseline_scenario, changes, **base_kwargs)
        turn.calculated.append(changes.changed())
        if changes.etf_product_code is not None and _changed_product(result) is None:
            # 계획 종목을 몰라 넘긴 키를 Spring이 계획과 같은 종목으로 봤다(C2-2). 남기면 비교 카드에 값 없는 종목 줄이
            # 생기므로 뺀다(#294)
            changes = changes.model_copy(update={"etf_product_code": None})
            if not changes.changed():
                raise CalcError(_SAME_PRODUCT_ERROR)
        turn.calc_run_id = result["calcRunId"]
        turn.condition_changes = changes.changed()
        if turn.req.text and turn.req.history:
            # 직전 발화의 금액을 이어받아 계산했으면 그 발화를 숫자 근거에 더한다(#260)
            user_texts = [h.content for h in turn.req.history if h.role == "user"]
            turn.number_evidence.extend(carried_amount_sources(turn.req.text, user_texts, changes))
        changed_obj = result.get("changed")
        if isinstance(changed_obj, dict):
            turn.changed_already_exceeded = changed_obj.get("status") == "ALREADY_EXCEEDED"
        display = result.get("display")
        if isinstance(display, dict):
            turn.number_evidence.extend(v for v in display.values() if isinstance(v, str))
            if changes.etf_product_code is not None:
                names = (display.get(calc_condition_key(side, ETF_NAME)) for side in CALC_SIDES)
                turn.compared_etf_names = tuple(n for n in names if isinstance(n, str))
            if "goal_amount" in turn.condition_changes:
                cur_goal = display.get(calc_condition_key(CURRENT_SIDE, GOAL_AMOUNT))
                chg_goal = display.get(calc_condition_key(CHANGED_SIDE, GOAL_AMOUNT))
                if isinstance(cur_goal, str) and isinstance(chg_goal, str):
                    turn.goal_changed_note_params = (cur_goal, chg_goal)
        if dropped:
            # 모델이 뺀 조건을 계산에 쓴 것처럼 답하지 않게 알린다(서버 사실 원칙)
            result = {**result, "excludedConditions": dropped, "note": EXCLUDED_NOTE}
        # 종목만 바꿨고 그 비교가 ETF를 쓰지 않을 때만 붙인다. 다른 조건도 바꿨으면 결과가 달라지므로 붙이지 않는다
        if changes.changed().keys() == {"etf_product_code"} and _changed_savings_ratio(result) == _ALL_SAVINGS_PCT:
            result = {**result, "etfNote": ETF_ZERO_NOTE}
        if changes.changed().keys() == {"goal_custom_name"}:
            result = {**result, "goalNameNote": GOAL_NAME_ONLY_NOTE}
        return json.dumps(result, ensure_ascii=False)

    async def _search_documents(self, turn: Turn, args: dict[str, Any]) -> str:
        doc_type = None if args["doc_type"] == "ANY" else DocType(args["doc_type"])
        etf_codes = search_etf_codes(turn.req, doc_type, args.get("etf_codes"))
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


def _changed_product(result: dict[str, Any]) -> object:
    """계산 응답 conditions.changed.etfProductCode(C2-3). Spring이 종목을 바꾸지 않은 것으로 봤으면 없다."""
    conditions = result.get("conditions")
    changed = conditions.get(CHANGED_SIDE) if isinstance(conditions, dict) else None
    return changed.get(ETF_CODE) if isinstance(changed, dict) else None


def _changed_savings_ratio(result: dict[str, Any]) -> object:
    """계산 응답 conditions.changed.savingsRatioPct(C2-3). 없으면 None."""
    conditions = result.get("conditions")
    changed = conditions.get(CHANGED_SIDE) if isinstance(conditions, dict) else None
    return changed.get(SAVINGS_RATIO) if isinstance(changed, dict) else None


def _errors_for_model(errors: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """오류 항목은 그대로 넘긴다. 단 표시 문자열이 빠진 OUT_OF_RANGE는 모델이 원 단위 값을 환산하지 못하게
    min·max를 뺀다(계약 위반 방어, C3-3)."""
    return [
        {k: v for k, v in item.items() if k not in ("min", "max")}
        if item.get("reason") == "OUT_OF_RANGE" and not (item.get("minDisplay") and item.get("maxDisplay"))
        else item
        for item in errors
    ]


def _empty_recalc_without_calc(turn: Turn, final: FinalAnswer) -> bool:
    return final.message_type == "RECALC_RESULT" and turn.calc_run_id is None and not final.content.strip()


def _refusal(req: ChatRequest) -> ChatResponse:
    return ChatResponse(
        message_type="OUT_OF_SCOPE", content=out_of_scope_content(), baseline_scenario=req.baseline_scenario
    )


def _answer_text(resp: Any) -> str:
    return "".join(b.text for b in resp.content if isinstance(b, BetaTextBlock))


def _trace_tool(turn: Turn, block: BetaToolUseBlock, result: Any) -> None:
    trace.info(
        "tool call: message_id=%s name=%s input=%s result=%s",
        turn.req.message_id, block.name, _json(block.input), result if isinstance(result, str) else _json(result),
    )


def _trace_model_answer(req: ChatRequest, resp: Any, *, attempt: int) -> None:
    """파싱·검사 전 모델 원문. 형식 오류·잘린 답·숫자 불일치·금지 표현으로 거절된 답도 여기 남는다.
    도구만 부른 응답은 원문이 없어 남기지 않는다(도구 줄에 남는다)."""
    text = _answer_text(resp)
    if text:
        trace.info(
            "model answer: message_id=%s attempt=%s stop=%s body=%s", req.message_id, attempt, resp.stop_reason, text
        )


def _parse_final(resp: Any) -> FinalAnswer:
    """최종 응답 파싱. 오류 detail에는 오류 종류와 위치만 싣는다(P-16). 원문은 model answer 로그에 있다(#316)."""
    try:
        return FinalAnswer.model_validate_json(_answer_text(resp))
    except ValidationError as e:
        raise AgentError(ErrorCode.BAD_OUTPUT, ",".join(f"{err['type']}@{err['loc']}" for err in e.errors())) from None
