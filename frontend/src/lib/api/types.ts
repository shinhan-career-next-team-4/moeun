// 백엔드 DTO와 1:1. 금액은 원(number), 비중·금리는 %.

export type GoalType = "JEONSE" | "WEDDING" | "INDEPENDENCE" | "TRAVEL" | "CUSTOM";
export type RatioChoice = "BASE" | "KEEP_CURRENT";
export type ChangeSource = "ALTERNATIVE" | "COMPARE" | "CHAT";

export type ErrorCode =
  | "REQUIRED"
  | "OUT_OF_RANGE"
  | "NOT_UNIT"
  | "INVALID_SCALE"
  | "TOO_LONG"
  | "BLANK"
  | "ONE_OF_PERIOD_OR_SAVING"
  | "EXISTING_EXCEEDS_SAVING"
  | "RATIO_CHOICE_REQUIRED"
  | "FIELD_NOT_CHANGEABLE"
  | "TOO_MANY_CHANGES";

export interface FieldErrorDto {
  field: string;
  code: ErrorCode;
}

export interface PlanInputDto {
  goalType: GoalType;
  goalCustomName: string | null;
  goalAmount: number;
  targetPeriodMonths: number | null;
  heldAssets: number;
  monthlySaving: number | null;
  savingsRatePct: number | null;
  existingSavingsMonthly: number | null;
  existingEtfMonthly: number | null;
  ratioChoice: RatioChoice | null;
}

export interface PlanView extends PlanInputDto {
  savingsRatioPct: number | null;
  calcStartMonth: string;
  baselineScenario: "GOOD" | "AVG" | "BAD";
  revision: number;
  canUndo: boolean;
  updatedAt: string;
}

export interface ConditionOverride {
  monthlySaving?: number;
  targetPeriodMonths?: number;
  savingsRatioPct?: number;
  savingsRatePct?: number;
}

export interface ApplyRequest {
  source: ChangeSource;
  changes: ConditionOverride;
  expectedRevision: number;
}

export interface FieldRule {
  required: boolean;
  min: number;
  max: number;
  unit: "KRW" | "MONTH" | "PERCENT" | "CHAR";
  scale?: number;
  step?: number;
}

export type RuleField =
  | "goalCustomName"
  | "goalAmount"
  | "targetPeriodMonths"
  | "heldAssets"
  | "monthlySaving"
  | "savingsRatePct"
  | "existingSavingsMonthly"
  | "existingEtfMonthly"
  | "savingsRatioPct";

export interface GoalTypeRule {
  code: GoalType;
  defaultAmount?: number;
  defaultPeriodMonths?: number;
}

export interface InputRules {
  fields: Record<RuleField, FieldRule>;
  goalTypes: GoalTypeRule[];
  periodPresets: number[];
  baseSavingsRatioPct: number;
}

export interface MeResponse {
  loginId: string;
  hasPlan: boolean;
}
