// copy/copy.ko.yml 값을 옮긴 상수. 프론트에는 문구표 로더가 없어 값을 복사해 두고, copy.test.ts가 파일과 비교해 어긋남을 잡는다.
export const CONDITION_LABELS: Record<string, string> = {
  monthlySavingAmount: "월 저축액",
  savingsRatioPct: "저축 비중",
  savingsRateInputPct: "적금 금리",
  targetPeriodMonths: "목표 기간",
};

export const GOAL_LABEL = "목표 금액";

/** 목표 이름 행 라벨(condition.goalCustomName, #345). */
export const GOAL_NAME_LABEL = "목표 이름";

export const ETF_LABEL = "ETF 종목";

/** 비교 카드 제목과 변화 방향 문구(#284). */
export const DIRECTION_COPY = {
  title: "{conditions}을 바꾼 결과",
  better: "좋아졌어요",
  worse: "나빠졌어요",
  same: "변화 없어요",
  increased: "늘렸어요",
  decreased: "줄였어요",
} as const;

export const ACHIEVEMENT_CHANGE_LABELS: Record<string, string> = {
  STAYS_ACHIEVED: "계속 달성해요",
  STAYS_MISSED: "계속 미달이에요",
  MISS_TO_ACHIEVE: "미달에서 달성으로 바뀌어요",
  ACHIEVE_TO_MISS: "달성에서 미달로 바뀌어요",
};

export const TOAST_COPY = {
  applyStaleComparison: "계획이 바뀌었거나 더 최근 비교가 있어요. 최신 비교를 다시 받아 적용해 주세요",
  applyPlanChanged: "계획이 다른 곳에서 바뀌었어요",
  applyNotApplicable: "지금 기준으로는 적용할 수 없는 값이에요",
  applyFailed: "적용하지 못했어요",
} as const;

export type ToastKey = keyof typeof TOAST_COPY;

export const LOAD_FAILED = "비교 결과를 불러오지 못했어요";

export const APPLY_LABEL = "내 계획으로 적용";
export const COLLAPSED_LABEL = "이전 결과";
