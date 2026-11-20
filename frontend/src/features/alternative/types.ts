/**
 * 대안 영역(FR-ALT-01~04, 와이어프레임 03). 카드 본문 값은 모두 서버가 만든 display 문자열이다.
 *
 * 대안 산출 API(#66)는 아직 없고, 카드 본문 4줄의 값 출처도 Q-089·Q-103 결정 대기다.
 * 그래서 여기 모양은 화면이 받을 문자열 자리만 정하고, 값이 없으면 카드는 빈 카드로 그린다.
 * 프론트는 어떤 값도 계산하지 않는다(NFR-INT-01).
 */
export type AlternativeKey = "monthlySavingAmount" | "targetPeriodMonths" | "savingsRatioPct";

/** 카드 본문 4줄. 모두 서버 display 문자열이고, 없는 줄은 그리지 않는다. */
export interface AlternativeValues {
  /** `월 120만원 → 121만원` 같은 '바뀌는 조건' 줄 */
  changedCondition?: string;
  /** `+1만원` 같은 '변화' 줄 */
  change?: string;
  /** 기준 시나리오(평균)의 세후 예상 자산 */
  avgAfterTaxAsset?: string;
  /** `+약 17만원 달성` 같은 목표 대비 */
  gapToGoal?: string;
  /** `약 4,856만원 · 미달` 같은 나빴던 때 */
  badAfterTaxAsset?: string;
}

export type AlternativeValuesByKey = Partial<Record<AlternativeKey, AlternativeValues>>;
