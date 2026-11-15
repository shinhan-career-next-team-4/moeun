import asyncio
import json
import logging
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

from app.config import Effort
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
OUT_OF_SCOPE_COPY = (
    "이 질문은 모은에서 다루지 않아요. 목표 금액, 기간, 월 저축액 같은 조건을 바꿔 보고 싶다면 말씀해 주세요."
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
    "goal_amount": _nullable("integer", description="목표 금액(원)"),
    "target_period_months": _nullable("integer", description="목표 기간(개월)"),
    "held_assets": _nullable("integer", description="이미 모은 돈(원)"),
    "monthly_saving_amount": _nullable("integer", description="월 저축액(원)"),
    "savings_rate_input_pct": _nullable("number", description="적금 연 금리(%)"),
    "existing_monthly_contribution": _nullable("integer", description="기존 적금 월 납입액(원)"),
    "savings_ratio_pct": _nullable("integer", description="적금 비중(%), 나머지는 ETF"),
}

TOOLS: list[BetaToolParam] = [
    {
        "name": "recalculate",
        "description": (
            "사용자의 현재 계획에서 일부 조건만 바꿔 Spring 계산 엔진으로 다시 계산한다. "
            "바꾸지 않는 조건은 null로 둔다. 금액은 원 단위 정수, 기간은 개월 수."
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

# 도구 실패로 보고 모델에게 is_error 결과로 돌려줄 예외. 그 밖의 예외는 요청 실패로 올린다
_TOOL_ERRORS = (CalcError, ValidationError, KeyError, ValueError, RuntimeError)


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
        if not changes.changed():
            raise CalcError("바꿀 조건이 없습니다. 무엇을 바꿀지 사용자에게 되물으세요.")
        result = await self._calc.recalculate(turn.req.member_id, turn.req.baseline_scenario, changes)
        turn.calc_run_id = result["calcRunId"]
        turn.condition_changes = changes.changed()
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
