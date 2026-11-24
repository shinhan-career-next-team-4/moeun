import type { ComparisonView, ConditionChanges } from "../compare";

/**
 * 대안 영역(FR-ALT-01~04, 와이어프레임 03). 카드 본문 값은 모두 서버가 만든 display 문자열이다.
 *
 * 값은 대안 API(A-1 `POST /api/alternatives`, #66)의 display에서 오고 줄별 키는 D-096을 따른다(requestAlternatives).
 * 값이 없으면 카드는 빈 카드로 그린다.
 * 프론트는 어떤 값도 계산하지 않고 어떤 판정도 하지 않는다(NFR-INT-01).
 */
export type AlternativeKey = "monthlySavingAmount" | "targetPeriodMonths" | "savingsRatioPct";

/** 카드 본문. 모두 서버 display 문자열이고, 없는 줄은 그리지 않는다. */
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
  /** 허용 범위 안에서 목표를 달성하는 값을 찾지 못했다(#343). 카드는 '달성이 어려워요' 안내만 보이고 버튼은 막힌다 */
  unreachable?: boolean;
  /** 서버가 변동이 커진다고 알려 준 대안(W-10). 판정은 화면이 하지 않는다 */
  volatilityWarning?: boolean;
  /** '비교하기'가 비교 영역에 채우고 '내 계획으로 적용'이 P-6으로 보낼 조건 */
  changes?: ConditionChanges;
  /** 적용 확인창이 그릴 비교 모양(바뀐 조건과 display) */
  comparison?: ComparisonView;
}

export type AlternativeValuesByKey = Partial<Record<AlternativeKey, AlternativeValues>>;
