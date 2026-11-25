import type { ComparedKey, ComparisonView } from "./types";

/**
 * 바꾼 조건 한 줄의 현재·바꾼 값(비교표와 적용 확인창이 함께 쓴다).
 *
 * 숫자 조건은 서버 display 문자열을 그대로 쓴다(NFR-INT-01). ETF 종목만 두 계산의 가정에 담긴
 * 화면 이름을 쓴다 — 비교 응답의 display에는 `assumptions.*` 키가 없기 때문이다(계약 C2-6).
 */
export function conditionValues(
  view: ComparisonView,
  key: ComparedKey,
): { current?: string; changed?: string } {
  if (key === "etfProductCode") {
    return {
      current: view.current?.assumptions?.etfProductName ?? undefined,
      changed: view.changed?.assumptions?.etfProductName ?? undefined,
    };
  }
  return {
    current: view.display[`conditions.current.${key}`],
    changed: view.display[`conditions.changed.${key}`],
  };
}
