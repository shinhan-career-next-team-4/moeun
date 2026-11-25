from decimal import Decimal
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictFloat, StrictInt, StrictStr, field_validator
from pydantic.alias_generators import to_camel

from app.core.contract import DISPLAY_KEY as DISPLAY_KEY
from app.core.contract import Scenario as Scenario


class ApiModel(BaseModel):
    """Spring(Jackson)과 주고받는 모델. JSON은 camelCase, 파이썬은 snake_case."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, serialize_by_alias=True)


class ChatMode(StrEnum):
    QUESTION = "QUESTION"   # 사용자 질문 (FR-AI-06/07/09)
    EXPLAIN = "EXPLAIN"     # 계산 결과 설명 / 설명 다시 요청 (FR-AI-01/02/05)


class DocType(StrEnum):
    EDU = "EDU"
    DISCLOSURE = "DISCLOSURE"


class ConditionChanges(ApiModel):
    """대화로 바꿀 수 있는 조건 7개(D-092·D-097·#345). None = 바꾸지 않음. etf_product_code(ETF 종목 코드)와
    goal_custom_name(목표 이름, 계산에 쓰지 않음)만 문자열이다.

    타입만 검사한다. 범위 검증은 Spring 한 곳에서 한다(계약 원칙 2, D-033).
    """

    model_config = ConfigDict(extra="forbid")

    goal_amount: StrictInt | None = None
    monthly_saving_amount: StrictInt | None = None
    savings_ratio_pct: StrictInt | None = None
    savings_rate_input_pct: StrictFloat | StrictInt | None = Field(default=None, allow_inf_nan=False)
    target_period_months: StrictInt | None = None
    etf_product_code: StrictStr | None = None
    goal_custom_name: StrictStr | None = None

    def changed(self) -> dict[str, Any]:
        # chat_message.condition_changes에 저장되는 값. snake_case 그대로 둔다(camelCase 변환은 Spring, C12b-14)
        return self.model_dump(exclude_none=True, by_alias=False)


class ConditionChangeEntry(ApiModel):
    """대화 조건 누적(#418, D-103)의 변경 목록 한 항목. Spring이 순서대로 저장해 넘긴다.
    field는 camelCase 조건 키(conditionChanges와 같은 7개), before는 바꾸기 전 값(계획에 값이 없었으면 null)이다."""

    field: str
    before: StrictInt | StrictFloat | StrictStr | None = None
    after: StrictInt | StrictFloat | StrictStr


class HistoryItem(ApiModel):
    role: Literal["user", "assistant"]
    content: str


class PlanScenario(ApiModel):
    scenario: Scenario
    is_achieved: bool


class PlanAssumptions(ApiModel):
    is_default_savings_rate: bool
    scenario_method: str | None      # 키는 필수, ETF 0%면 null
    excluded_items: list[str] | None   # 키는 필수, 값은 null 가능
    etf_gain_tax_rate_pct: Decimal | None = None   # 비과세 판정(0)에만 쓴다. 모델 입력에는 넣지 않는다
    etf_product_code: str | None = None   # 계산에 쓴 ETF 종목 코드(#150). 검색 종목 필터에만 쓴다. 모델 입력에는 넣지 않는다


class PlanConditions(ApiModel):
    etf_product_code: str | None = None   # 계획에 저장된 ETF 종목(#297). ETF 0%여도 있다. 모델 입력에는 넣지 않는다


class PlanResult(ApiModel):
    status: Literal["CALCULATED", "ALREADY_EXCEEDED"]
    scenarios: list[PlanScenario]
    assumptions: PlanAssumptions


class PlanContext(ApiModel):
    """Spring이 만든 현재 계획 요약(계약 C1a-8). 모델이 쓰는 필드만 선언하고 나머지(raw 금액·계정 필드)는 무시한다."""

    calc_run_id: int | None = None
    conditions: PlanConditions | None = None
    result: PlanResult
    display: dict[str, Any]

    @field_validator("display")
    @classmethod
    def _keep_allowed_display(cls, display: dict[str, Any]) -> dict[str, Any]:
        return {k: v for k, v in display.items() if isinstance(v, str) and DISPLAY_KEY.fullmatch(k)}


class ChatRequest(ApiModel):
    """Spring → ai-server. 사용자 JWT는 넘기지 않고 Spring이 member_id를 담는다."""

    member_id: int
    message_id: int                              # Spring이 만든 PENDING chat_message.id (로그 추적용)
    mode: ChatMode
    text: str | None = Field(default=None, max_length=2000)   # QUESTION일 때 사용자 발화
    baseline_scenario: Scenario
    calc_run_id: int | None = None               # EXPLAIN일 때 설명할 계산
    plan_context: PlanContext | None = None      # 현재 조건과 계산 결과 요약. 모델에는 display 문자열만 넘긴다
    # QUESTION: 질문을 접수한 순간의 계획 버전(#396). 재계산이 이 버전 기준이라
    # 답을 기다리는 사이 계획이 바뀌어도 어긋나지 않는다
    base_plan_version_id: int | None = None
    history: list[HistoryItem] = Field(default_factory=list, max_length=20)
    # QUESTION: 이 대화에서 앞서 바꾼 조건의 변경 목록(#418, D-103). 다음 계산의 비교 기준이 된다
    accumulated_changes: list[ConditionChangeEntry] = Field(default_factory=list, max_length=100)


MessageType = Literal["ANSWER", "EXPLANATION", "RECALC_RESULT", "CLARIFY", "OUT_OF_SCOPE"]


class ChatResponse(ApiModel):
    """ai-server → Spring. Spring이 chat_message를 COMPLETED로 갱신하고 citation을 저장한다."""

    message_type: MessageType
    content: str
    baseline_scenario: Scenario
    calc_run_id: int | None = None
    condition_changes: dict[str, Any] | None = None
    citation_chunk_ids: list[int] = Field(default_factory=list)


class SearchRequest(ApiModel):
    query: str = Field(min_length=1, max_length=500)
    doc_type: DocType | None = None
    top_k: int | None = Field(default=None, ge=1, le=20)


class RetrievedChunk(ApiModel):
    chunk_id: int
    content: str
    title: str
    source_url: str
    doc_type: DocType
    org_name: str
    distance: float
