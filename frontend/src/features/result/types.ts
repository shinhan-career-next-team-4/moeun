import type { PlanStatus, PlanView } from "../../api/planStore";

export type ScenarioCode = "GOOD" | "AVG" | "BAD";

/** 누적 납입 원금의 월별 점(`result.principalPoints`, C12a-6-2). 서버 값을 그대로 쓴다 */
export interface PrincipalPoint {
  month: string;
  principal: number;
}

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
  /** 계산에 쓴 ETF 종목 코드. ETF 0%면 null이거나 없다(#150) */
  etfProductCode?: string | null;
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
  /** 되돌릴 직전 계획이 있는지. '직전 계획으로 되돌리기' 버튼에 쓴다(D-095, #263) */
  undoAvailable: boolean;
  plan: ResultPlanBody;
  result: {
    status: PlanStatus;
    goalAmount: number;
    calcStartMonth: string;
    targetMonth: string;
    scenarios: ScenarioResult[];
    /** 누적 납입 원금 월별 점(하단 막대 그래프용). 보유 자산 + 월 저축액 × 경과 개월을 서버가 내려 준다 */
    principalPoints?: PrincipalPoint[];
    assumptions: Assumptions;
  };
  display: Record<string, string>;
}
