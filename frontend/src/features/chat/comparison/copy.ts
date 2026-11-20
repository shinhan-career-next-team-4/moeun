// copy/copy.ko.yml 값을 옮긴 상수. 프론트에는 문구표 로더가 없어 값을 복사해 두고, copy.test.ts가 파일과 비교해 어긋남을 잡는다.
export const CONDITION_LABELS: Record<string, string> = {
  monthlySavingAmount: "월 저축액",
  savingsRatioPct: "저축 비중",
  savingsRateInputPct: "적금 금리",
  targetPeriodMonths: "목표 기간",
};

export const ACHIEVEMENT_CHANGE_LABELS: Record<string, string> = {
  STAYS_ACHIEVED: "계속 달성해요",
  STAYS_MISSED: "계속 미달이에요",
  MISS_TO_ACHIEVE: "미달에서 달성으로 바뀌어요",
  ACHIEVE_TO_MISS: "달성에서 미달로 바뀌어요",
};

export const TOAST_COPY = {
  applyStaleComparison: "가장 최근 비교 결과만 적용할 수 있어요",
  applyPlanChanged: "계획이 다른 곳에서 바뀌었어요",
  applyNotApplicable: "지금 기준으로는 적용할 수 없는 값이에요",
  applyFailed: "적용하지 못했어요",
} as const;

export type ToastKey = keyof typeof TOAST_COPY;

export const LOAD_FAILED = "비교 결과를 불러오지 못했어요";

export const APPLY_LABEL = "내 계획으로 적용";
export const COLLAPSED_LABEL = "이전 결과";
