// C12a-5-1: CUSTOM은 FR-INP-02 미구현 동안 화면에서 숨긴다.
export const GOAL_TYPES = ["HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND"] as const;

export type GoalType = (typeof GOAL_TYPES)[number];

export interface PlanForm {
  goalType: GoalType | null;
  goalAmount: string;
  periodYears: string;
  periodMonths: string;
  heldAssets: string;
  monthlySavingAmount: string;
  savingsRateInputPct: string;
}

export type FormField =
  | "goalType"
  | "goalAmount"
  | "targetPeriodMonths"
  | "heldAssets"
  | "monthlySavingAmount"
  | "savingsRateInputPct";

export type FormErrors = Partial<Record<FormField, string>>;

export interface PutPlanRequest {
  expectedRevision: number | null;
  goalType: GoalType;
  goalCustomName: null;
  goalAmount: number;
  targetPeriodMonths: number;
  heldAssets: number;
  monthlySavingAmount: number;
  savingsRateInputPct: number | null;
}
