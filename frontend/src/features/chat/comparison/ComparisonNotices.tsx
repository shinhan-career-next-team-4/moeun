import { TOAST_COPY } from "./copy";
import type { ComparisonState } from "./useComparison";

/**
 * 적용 오류 안내와 되돌리기 안내. 비어 있으면 아무것도 그리지 않는다.
 * 적용 뒤의 "조건을 적용했어요 / 되돌리기" 토스트는 더 이상 그리지 않는다(#394). 되돌리기는 결과 화면 조건 바의
 * '직전 계획으로 되돌리기' 버튼이 반짝여 알린다(#361).
 */
export function ComparisonNotices({ comparison }: { comparison: ComparisonState }) {
  const { snapshot } = comparison;
  return (
    <>
      {snapshot.toast !== null && (
        <p data-testid="chat-comparison-toast" role="alert">
          {TOAST_COPY[snapshot.toast]}
        </p>
      )}
      {snapshot.feedback !== null && <p data-testid="chat-comparison-feedback">{snapshot.feedback}</p>}
    </>
  );
}
