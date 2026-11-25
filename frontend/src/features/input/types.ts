/** C12a-5-1: `CUSTOM`(직접 입력)을 고르면 목표 이름(`goalCustomName`)을 함께 보낸다(FR-INP-02, D-074·D-081). */
export const GOAL_TYPES = ["HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND", "CUSTOM"] as const;

export type GoalType = (typeof GOAL_TYPES)[number];

export interface PlanForm {
  goalType: GoalType | null;
  /** 목표 종류가 CUSTOM일 때만 쓰는 목표 이름. 다른 종류로 바꿔도 값은 남겨 두고, 요청에는 CUSTOM일 때만 싣는다. */
  goalCustomName: string;
  goalAmount: string;
  /** D-084: false면 화면이 목표 금액·보유 자산·월 저축액으로 기간을 추천해 채운다. */
  periodKnown: boolean;
  /** D-084: false면 화면이 목표 금액·보유 자산·기간으로 월 저축액을 추천해 채운다. */
  savingKnown: boolean;
  periodYears: string;
  periodMonths: string;
  heldAssets: string;
  monthlySavingAmount: string;
  savingsRateInputPct: string;
  /** D-085·D-086: '지금 하고 있는 저축' 토글. OFF면 요청 savingsRatioPct가 null이다 */
  existingSavingOn: boolean;
  /** 적금 월 납입액(만원). 비중을 정하는 데만 쓰고 저장하지 않는다(D-043) */
  existingSavingBank: string;
  /** ETF 월 납입액(만원) */
  existingSavingEtf: string;
}

export type FormField =
  | "goalType"
  | "goalCustomName"
  | "goalAmount"
  | "targetPeriodMonths"
  | "heldAssets"
  | "monthlySavingAmount"
  | "savingsRateInputPct"
  | "existingSaving";

export type FormErrors = Partial<Record<FormField, string>>;

export interface PutPlanRequest {
  expectedRevision: number | null;
  goalType: GoalType;
  /** CUSTOM이면 trim한 목표 이름, 아니면 null(D-081) */
  goalCustomName: string | null;
  goalAmount: number;
  targetPeriodMonths: number;
  heldAssets: number;
  monthlySavingAmount: number;
  savingsRateInputPct: number | null;
  /** C12a-5-1: 키가 항상 있다. null = 사용자가 정한 비중 없음(기본 비중) */
  savingsRatioPct: number | null;
}
