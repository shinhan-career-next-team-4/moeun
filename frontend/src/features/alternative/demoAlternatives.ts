import type { AlternativeValuesByKey } from "./types";

/**
 * 목 모드 전용 대안 값(#149). 대안 산출 API(#66)가 생기면 이 파일을 지우고 서버 응답을 쓴다.
 *
 * 실서버 모드에서는 쓰지 않는다. 와이어프레임 03의 카드 값을 그대로 옮긴 데모 문자열이고,
 * 화면이 계산해 만든 값이 아니다(NFR-INT-01). 역산 값의 출처는 Q-089·Q-103 결정 대기다.
 */
const DEMO: AlternativeValuesByKey = {
  monthlySavingAmount: {
    changedCondition: "월 120만원 → 121만원",
    change: "+약 1만원",
    avgAfterTaxAsset: "약 5,017만원",
    gapToGoal: "+약 17만원 달성",
    badAfterTaxAsset: "약 4,856만원 · 미달",
    changes: { monthlySavingAmount: 12100000 },
  },
  targetPeriodMonths: {
    changedCondition: "3년 → 3년 1개월",
    change: "+1개월",
    avgAfterTaxAsset: "약 5,100만원",
    gapToGoal: "+약 100만원 달성",
    badAfterTaxAsset: "약 4,935만원 · 미달",
    changes: { targetPeriodMonths: 37 },
  },
  savingsRatioPct: {
    changedCondition: "적금 83 : ETF 17 → 적금 50 : ETF 50",
    change: "ETF +33%p",
    avgAfterTaxAsset: "약 5,040만원",
    gapToGoal: "+약 40만원 달성",
    badAfterTaxAsset: "약 4,610만원 · 미달",
    volatilityWarning: true,
    changes: { savingsRatioPct: 50 },
  },
};

/** 목 모드에서만 대안 값을 돌려준다. 실서버 모드에서는 빈 카드로 둔다. */
export function demoAlternatives(mode: "mock" | "live"): AlternativeValuesByKey | undefined {
  return mode === "mock" ? DEMO : undefined;
}
