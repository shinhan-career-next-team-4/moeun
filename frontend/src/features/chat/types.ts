import type { PlanStatus } from "../../api/planStore";

// F-1·F-2 응답 모양(계약 C12b-3~5). 계약에 없는 필드는 적지 않는다.
export type ChatMessageType =
  | "QUESTION"
  | "ANSWER"
  | "EXPLANATION"
  | "RECALC_RESULT"
  | "CLARIFY"
  | "OUT_OF_SCOPE"
  | "FAILURE";

export interface ChatCitation {
  title: string;
  sourceUrl: string;
  orgName: string;
}

export interface ChatMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  messageType: ChatMessageType | null;
  status: "PENDING" | "COMPLETED" | "FAILED";
  content: string | null;
  citations: ChatCitation[];
  createdAt: string;
  /** 완료된 결과 설명에만 있다. 그 설명의 계산에 ETF가 들어갔는지(C12b-3, #305). */
  etfIncluded?: boolean;
}

/** GET /api/chat/messages 응답(F-2). */
export interface ChatPage {
  messages: ChatMessage[];
  hasPending: boolean;
  answerPending: boolean;
}

/** POST /api/chat/messages 202 응답(F-1). */
export interface ChatAccepted {
  userMessageId: number;
  assistantMessageId: number;
}

export interface ChatPollingState {
  messages: ChatMessage[];
  hasPending: boolean;
  answerPending: boolean;
  pollingFailed: boolean;
}

export type ScenarioName = "GOOD" | "AVG" | "BAD";

export type ComparisonConditionKey =
  | "goalAmount"
  | "monthlySavingAmount"
  | "savingsRatioPct"
  | "savingsRateInputPct"
  | "targetPeriodMonths";

export interface ComparisonConditionValues {
  monthlySavingAmount: number;
  savingsRatioPct: number;
  savingsRateInputPct: number;
  targetPeriodMonths: number;
}

export interface ComparisonScenario {
  scenario: ScenarioName;
  annualReturnPct: number;
  afterTaxAsset: number;
  principal: number;
  afterTaxGain: number;
  gapToGoal: number;
  isAchieved: boolean;
  goalReachMonth: string | null;
}

export interface ComparisonSide {
  status: PlanStatus;
  goalAmount: number;
  calcStartMonth: string;
  targetMonth: string;
  baselineScenario: ScenarioName;
  scenarios: ComparisonScenario[];
  requiredMonthlySaving: number | null;
  assumptions: Record<string, unknown>;
  dataVersions: Record<string, unknown>;
}

export interface ComparisonDiffItem {
  scenario: ScenarioName;
  afterTaxAssetDelta: number;
  principalDelta: number;
  afterTaxGainDelta: number;
  achievementChange: "STAYS_ACHIEVED" | "STAYS_MISSED" | "MISS_TO_ACHIEVE" | "ACHIEVE_TO_MISS";
}

/** GET /api/chat/messages/:id/comparison 응답(F-4, D-090·D-092). */
export interface ComparisonView {
  messageId: number;
  applicable: boolean;
  changedKeys: ComparisonConditionKey[];
  conditions: {
    current: ComparisonConditionValues;
    changed: ComparisonConditionValues;
  };
  current: ComparisonSide;
  changed: ComparisonSide;
  diff: {
    baselineScenario: ScenarioName;
    byScenario: ComparisonDiffItem[];
  };
  display: Record<string, string>;
}
