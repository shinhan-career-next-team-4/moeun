import type { PlanStatus, PlanView } from "../../api/planStore";

export type ScenarioCode = "GOOD" | "AVG" | "BAD";

/** 누적 납입 원금의 월별 점(`result.principalPoints`, C12a-6-4). 서버 값을 그대로 쓴다 */
export interface PrincipalPoint {
  month: string;
  principal: number;
}

/** 그래프에서 월을 눌렀을 때 보여 줄 월별 표시 문자열(`result.monthlyDetails`, C12a-6-5). 모두 서버가 서식한 값이다 */
export interface MonthlyDetail {
  month: string;
  /** 월 라벨(예: "2026년 12월"). 가로축 양 끝 라벨과 같은 서식 */
  label: string;
  /** 그 달까지의 누적 납입 원금 */
  principal: string;
  /** 응답에 있는 시나리오만 담는다(ETF 0%는 AVG 하나) */
  afterTaxAsset: Partial<Record<ScenarioCode, string>>;
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
    /** 월을 눌렀을 때 보여 줄 월별 표시 문자열. 없으면 월 영역을 단추로 만들지 않는다 */
    monthlyDetails?: MonthlyDetail[];
    assumptions: Assumptions;
  };
  display: Record<string, string>;
}
