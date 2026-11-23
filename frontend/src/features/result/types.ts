import type { PlanStatus, PlanView } from "../../api/planStore";

export type ScenarioCode = "GOOD" | "AVG" | "BAD";

export interface MonthlyPoint {
  month: string;
  afterTaxAsset: number;
}

export interface ScenarioResult {
  scenario: ScenarioCode;
  afterTaxAsset: number;
  gapToGoal: number;
  isAchieved: boolean;
  goalReachMonth: string | null;
  monthlyPoints: MonthlyPoint[];
}

export interface Assumptions {
  savingsRatePct: number;
  isDefaultSavingsRate: boolean;
  savingsRateBaseMonth: string | null;
  savingsRatioPct: number;
  taxRatePct: number;
  scenarioMethod: string | null;
  sp500BaseDate: string | null;
  excludedItems: string[];
  /** ETF 종목의 매매차익 세율. ETF 0%면 null이거나 없다(#150) */
  etfGainTaxRatePct?: number | null;
}

export interface ResultPlanBody {
  goalType: string;
  goalCustomName: string | null;
  goalAmount: number;
  targetPeriodMonths: number;
  heldAssets: number;
  monthlySavingAmount: number;
  savingsRateInputPct: number | null;
  savingsRatioPct: number | null;
}

export interface ResultPlanView extends PlanView {
  baselineResultScenario: ScenarioCode | null;
  plan: ResultPlanBody;
  result: {
    status: PlanStatus;
    goalAmount: number;
    calcStartMonth: string;
    targetMonth: string;
    scenarios: ScenarioResult[];
    assumptions: Assumptions;
  };
  display: Record<string, string>;
}
