/**
 * 비교 영역(FR-CMP-01~03, 와이어프레임 03). 표의 숫자는 모두 서버 display 문자열이다(NFR-INT-01).
 *
 * 응답 모양은 대화 비교 결과(계약 C12b-6 `ChatComparisonView`)와 같다. 결과 화면에서 조건을 바꿔
 * 비교를 요청하는 API(#64)는 아직 없고, 생기면 같은 모양을 돌려주기로 되어 있다(#65 본문).
 */
export type ConditionKey =
  | "goalAmount"
  | "monthlySavingAmount"
  | "savingsRatioPct"
  | "savingsRateInputPct"
  | "targetPeriodMonths";

/** 요청의 바꾼 조건. 키는 계약의 camelCase 조건 키와 같다(CP-07, 1단계 5개) */
export type ConditionChanges = Partial<Record<ConditionKey, number>>;

export type AchievementChange = "STAYS_ACHIEVED" | "STAYS_MISSED" | "MISS_TO_ACHIEVE" | "ACHIEVE_TO_MISS";

export interface ComparisonDiffByScenario {
  scenario: "GOOD" | "AVG" | "BAD";
  achievementChange: AchievementChange;
}

export interface ComparisonView {
  applicable: boolean;
  /** 바뀐 조건 키. 순서는 보장되지 않는다(C12b-6) */
  changedKeys: ConditionKey[];
  diff: {
    baselineScenario: "GOOD" | "AVG" | "BAD";
    byScenario: ComparisonDiffByScenario[];
  };
  display: Record<string, string>;
}
