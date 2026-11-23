from collections.abc import Sequence
from typing import Any, Protocol

from anthropic.types.beta import BetaJSONOutputFormatParam, BetaToolParam
from pydantic import BaseModel

from app.rag.etf_codes import PRODUCT_DOC_CODES, PRODUCT_INDEX_NAMES
from app.schemas import ConditionChanges, DocType, MessageType, RetrievedChunk, Scenario


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
    # 종목은 정해진 코드만 고른다(D-097). 목록에 없는 종목을 말하면 계산하지 않고 되묻는다(프롬프트)
    # 타입 배열에 enum을 함께 두면 API가 스키마를 거절한다(400). 코드 enum과 null을 anyOf로 나눈다
    "etf_product_code": {
        "anyOf": [{"type": "string", "enum": list(PRODUCT_INDEX_NAMES)}, {"type": "null"}],
        "description": "ETF 종목 코드. "
        + ", ".join(f"{code}={name}" for code, name in PRODUCT_INDEX_NAMES.items()),
    },
}

TOOLS: list[BetaToolParam] = [
    {
        "name": "recalculate",
        "description": (
            "사용자의 현재 계획에서 일부 조건만 바꿔 Spring 계산 엔진으로 다시 계산한다. "
            "바꿀 수 있는 조건은 목표 금액·월 저축액·적금 비중·적금 금리·기한·ETF 종목 여섯 가지이고, "
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
