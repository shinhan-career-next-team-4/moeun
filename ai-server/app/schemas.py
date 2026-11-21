import re
from decimal import Decimal
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictFloat, StrictInt, field_validator
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    """Spring(Jackson)과 주고받는 모델. JSON은 camelCase, 파이썬은 snake_case."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, serialize_by_alias=True)


class Scenario(StrEnum):
    GOOD = "GOOD"
    AVG = "AVG"
    BAD = "BAD"


class ChatMode(StrEnum):
    QUESTION = "QUESTION"   # 사용자 질문 (FR-AI-06/07/09)
    EXPLAIN = "EXPLAIN"     # 계산 결과 설명 / 설명 다시 요청 (FR-AI-01/02/05)


class DocType(StrEnum):
    EDU = "EDU"
    DISCLOSURE = "DISCLOSURE"


class ConditionChanges(ApiModel):
    """대화로 바꿀 수 있는 조건 1단계 5개(D-090·D-092, D-040 대체). None = 바꾸지 않음.

    타입만 검사한다. 범위 검증은 Spring 한 곳에서 한다(계약 원칙 2, D-033).
    """

    model_config = ConfigDict(extra="forbid")

    goal_amount: StrictInt | None = None
    monthly_saving_amount: StrictInt | None = None
    savings_ratio_pct: StrictInt | None = None
    savings_rate_input_pct: StrictFloat | StrictInt | None = Field(default=None, allow_inf_nan=False)
    target_period_months: StrictInt | None = None

    def changed(self) -> dict[str, Any]:
        # chat_message.condition_changes에 저장되는 값. snake_case 그대로 둔다(camelCase 변환은 Spring, C12b-14)
        return self.model_dump(exclude_none=True, by_alias=False)


class HistoryItem(ApiModel):
    role: Literal["user", "assistant"]
    content: str


# 계약 C1a-8 display 허용 키. 여기에 맞고 값이 문자열인 항목만 받는다
DISPLAY_KEY = re.compile(
    r"goalAmount|calcStartMonth|targetMonth"
    r"|(?:GOOD|AVG|BAD)\.(?:afterTaxAsset|principal|afterTaxGain|gapToGoal|goalReachMonth)"
    r"|conditions\.(?:heldAssets|monthlySavingAmount|targetPeriodMonths|savingsRatioPct|savingsRateInputPct)"
    r"|assumptions\.(?:savingsRateBaseMonth|sp500BaseDate|taxRatePct|etfProductName|etfGainTaxRatePct)"
)


class PlanScenario(ApiModel):
    scenario: Scenario
    is_achieved: bool


class PlanAssumptions(ApiModel):
    is_default_savings_rate: bool
    scenario_method: str | None      # 키는 필수, ETF 0%면 null
    excluded_items: list[str] | None   # 키는 필수, 값은 null 가능
    etf_gain_tax_rate_pct: Decimal | None = None   # 비과세 판정(0)에만 쓴다. 모델 입력에는 넣지 않는다


class PlanResult(ApiModel):
    status: Literal["CALCULATED", "ALREADY_EXCEEDED"]
    scenarios: list[PlanScenario]
    assumptions: PlanAssumptions


class PlanContext(ApiModel):
    """Spring이 만든 현재 계획 요약(계약 C1a-8). 모델이 쓰는 필드만 선언하고 나머지(raw 금액·계정 필드)는 무시한다."""

    calc_run_id: int | None = None
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
    history: list[HistoryItem] = Field(default_factory=list, max_length=20)


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
