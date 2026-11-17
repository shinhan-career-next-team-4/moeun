import asyncio
import json
import logging
import re
from collections.abc import Awaitable, Callable
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
from app.llm.prompts import EXPLAIN_INSTRUCTION, SYSTEM_PROMPT
from app.schemas import (
    ChatMode,
    ChatRequest,
    ChatResponse,
    ConditionChanges,
    DocType,
    MessageType,
    RetrievedChunk,
    Scenario,
)
from app.tools.calc import CalcError

log = logging.getLogger(__name__)

FALLBACK_BETA = "server-side-fallback-2026-07-01"
MAX_CITATIONS = 5
# 문구표(#18) 로더가 들어오면 거기서 읽는다
OUT_OF_SCOPE_COPY = (
    "이 질문은 모은에서 다루지 않아요. 월 저축액·비중·적금 금리·기한 같은 조건을 바꿔 보고 싶다면 말씀해 주세요."
)


class Calculator(Protocol):
    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges) -> dict[str, Any]: ...


class DocumentSearch(Protocol):
    async def search(
        self, query: str, doc_type: DocType | None = None, top_k: int | None = None
    ) -> list[RetrievedChunk]: ...


def _nullable(type_: str, **extra: Any) -> dict[str, Any]:
    return {"type": [type_, "null"], **extra}


_CONDITION_FIELDS = {
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
            "바꿀 수 있는 조건은 월 저축액·적금 비중·적금 금리·기한 네 가지이고, 바꾸지 않는 조건은 null로 둔다. "
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
            },
            "required": ["query", "doc_type"],
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

# 도구 실패로 보고 모델에게 is_error 결과로 돌려줄 예외. 그 밖의 예외는 요청 실패로 올린다
_TOOL_ERRORS = (CalcError, ValidationError, KeyError, ValueError, RuntimeError)


# 이번 발화가 그 조건을 가리키는지 보는 느슨한 표현. 자연어를 해석하거나 계산하지 않고, 표현이 없을 때만 값을 뺀다.
# "개월"의 월, "원래"의 원, "한 달"의 달처럼 다른 조건의 글자가 겹치지 않게 정규식으로 가린다(M-3)
# 한글 수사. 이/일/사/오 한 글자는 이번·일단·사람·오늘 같은 어휘와 겹치므로 십·백·천이 붙은 것만 수사로 보고,
# 한 글자+만(일만 하자, 오늘은 이만)은 뒤에 원이 올 때만 금액으로 본다.
# 앞에 한글이 붙은 것(하십시오)은 제외하고, 뒤에는 조사·단위나 한글이 아닌 글자가 와야 한다(이만큼, 백화점, 십분 제외).
# 조사를 늘리면 세는 수가 늘어 필터가 꺼지는 쪽(이전 값이 남는 쪽)으로만 틀리므로 허용 목록을 넓게 둔다
_KO_DIGIT = "[일이삼사오육칠팔구]"
_KO_FOLLOW = (
    r"(?=원|으로|로|이|가|은|는|를|을|씩|짜리|퍼|프로|개월|년|달|도|면|에(?!요)|쯤|까지|부터|정도|나|밖에|마다|과|와|보다"
    r"|(?![가-힣]))"
)
_KO_TENS = rf"(?:{_KO_DIGIT}?[십백천])+{_KO_DIGIT}?"
_KO_MAN = rf"(?<![가-힣])(?:{_KO_TENS}만{_KO_FOLLOW}|{_KO_DIGIT}만(?=\s?원))"
_KO_NUMERAL = rf"{_KO_MAN}|(?<![가-힣]){_KO_TENS}{_KO_FOLLOW}"
_MONTHLY = re.compile(rf"(?<!개)월|매달|한\s?달|저축(?!\s*기간)|납입|적립|\d\s*만|{_KO_MAN}|만원|\d\s*원")
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


def _count_numbers(text: str) -> int:
    """발화 속 숫자 덩어리 수. 쉼표 금액(500,000)과 한 기간의 복수 단위(3년 6개월)는 하나로 센다."""
    return len(_NUMBER.findall(_PERIOD_COMPOUND.sub("0년", _THOUSANDS_COMMA.sub("", text))))


def _stated_conditions(text: str) -> set[str]:
    """발화가 가리키는 조건. %는 금리·비중 단어가 있으면 그쪽에만 붙이고, 둘 다 없으면 둘 모두로 본다."""
    ratio_words = bool(_RATIO_WORDS.search(text))
    rate_words = bool(_RATE_WORDS.search(text))
    percent = bool(_PERCENT.search(text))
    stated = {
        "monthly_saving_amount": bool(_MONTHLY.search(text)),
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


@dataclass
class _Turn:
    """한 요청 동안 도구가 만든 서버 쪽 사실. 모델 출력보다 이것을 믿는다."""

    req: ChatRequest
    calc_run_id: int | None = None
    condition_changes: dict[str, Any] | None = None
    retrieved_ids: list[int] = field(default_factory=list)


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
    ):
        self._client = client
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

        turn = _Turn(req)
        messages = build_messages(req)

        for _ in range(self._max_rounds):
            resp = await self._client.beta.messages.create(
                model=self._model,
                max_tokens=16000,
                system=SYSTEM_PROMPT,
                tools=TOOLS,
                messages=messages,
                output_config=self._output_config,
                cache_control={"type": "ephemeral"},
                betas=[FALLBACK_BETA],
                fallbacks="default",
            )

            match resp.stop_reason:
                case "refusal":
                    return ChatResponse(
                        message_type="OUT_OF_SCOPE", content=OUT_OF_SCOPE_COPY, baseline_scenario=req.baseline_scenario
                    )
                case "max_tokens":
                    raise AgentError("MAX_TOKENS")
                case "tool_use":
                    tool_uses = [b for b in resp.content if isinstance(b, BetaToolUseBlock)]
                    messages.append(BetaAssistantMessageParam(role="assistant", content=resp.content))
                    results = await asyncio.gather(*(self._call_tool(turn, b) for b in tool_uses))
                    messages.append(BetaUserMessageParam(role="user", content=results))   # 결과는 한 메시지에 모두
                case _:
                    text = "".join(b.text for b in resp.content if isinstance(b, BetaTextBlock))
                    try:
                        final = FinalAnswer.model_validate_json(text)
                    except ValidationError as e:
                        raise AgentError("BAD_OUTPUT", str(e)[:300]) from e
                    return assemble(turn, final)

        raise AgentError("TOO_MANY_ROUNDS")

    async def _call_tool(self, turn: _Turn, block: BetaToolUseBlock) -> BetaToolResultBlockParam:
        handler = self._tools.get(block.name)
        try:
            if handler is None:
                raise KeyError(f"unknown tool {block.name}")
            content = await handler(turn, dict(block.input))
        except _TOOL_ERRORS as e:
            log.info("tool %s failed for message %s: %s", block.name, turn.req.message_id, e)
            return {"type": "tool_result", "tool_use_id": block.id, "content": f"Error: {e}", "is_error": True}
        return {"type": "tool_result", "tool_use_id": block.id, "content": content}

    async def _recalculate(self, turn: _Turn, args: dict[str, Any]) -> str:
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
        result = await self._calc.recalculate(turn.req.member_id, turn.req.baseline_scenario, changes)
        turn.calc_run_id = result["calcRunId"]
        turn.condition_changes = changes.changed()
        if dropped:
            # 모델이 뺀 조건을 계산에 쓴 것처럼 답하지 않게 알린다(서버 사실 원칙)
            result = {**result, "excludedConditions": dropped, "note": EXCLUDED_NOTE}
        return json.dumps(result, ensure_ascii=False)

    async def _search_documents(self, turn: _Turn, args: dict[str, Any]) -> str:
        doc_type = None if args["doc_type"] == "ANY" else DocType(args["doc_type"])
        chunks = await self._retriever.search(args["query"], doc_type)
        turn.retrieved_ids.extend(c.chunk_id for c in chunks)
        payload = [c.model_dump(by_alias=False, exclude={"distance"}) for c in chunks]
        return json.dumps(payload, ensure_ascii=False)


def build_messages(req: ChatRequest) -> list[BetaMessageParam]:
    """기록은 user로 시작하고 역할이 번갈아야 한다. 같은 역할이 이어지면 합친다."""
    context = json.dumps(req.plan_context or {}, ensure_ascii=False)
    body = req.text if req.mode is ChatMode.QUESTION else EXPLAIN_INSTRUCTION
    current = (
        f"<plan_context>{context}</plan_context>\n"
        f"<baseline_scenario>{req.baseline_scenario.value}</baseline_scenario>\n"
        f"<request>{body}</request>"
    )

    turns: list[tuple[Literal["user", "assistant"], str]] = []
    for role, content in [*((h.role, h.content) for h in req.history), ("user", current)]:
        if not turns and role == "assistant":
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


def assemble(turn: _Turn, final: FinalAnswer) -> ChatResponse:
    """DB CHECK(ck_cm_recalc/explain/out_of_scope)를 만족하도록 모델 출력을 서버 사실로 보정한다."""
    req = turn.req
    allowed = set(turn.retrieved_ids)
    citations = list(dict.fromkeys(i for i in final.citation_chunk_ids if i in allowed))[:MAX_CITATIONS]

    message_type: MessageType = final.message_type
    calc_run_id: int | None = None
    changes: dict[str, Any] | None = None

    if turn.calc_run_id is not None and message_type != "OUT_OF_SCOPE":
        message_type, calc_run_id, changes = "RECALC_RESULT", turn.calc_run_id, turn.condition_changes
    elif req.mode is ChatMode.EXPLAIN:
        message_type, calc_run_id = "EXPLANATION", req.calc_run_id
    elif message_type in ("RECALC_RESULT", "EXPLANATION"):
        message_type = "ANSWER"

    return ChatResponse(
        message_type=message_type,
        content=final.content,
        baseline_scenario=req.baseline_scenario,
        calc_run_id=calc_run_id,
        condition_changes=changes,
        citation_chunk_ids=citations,
    )
