import { useCallback } from "react";
import { UndoToast } from "../../result/UndoToast";
import { TOAST_COPY } from "./copy";
import type { ComparisonState } from "./useComparison";

/** 적용 오류 안내, 되돌리기 토스트(#51), 되돌리기 안내. 비어 있으면 아무것도 그리지 않는다. */
export function ComparisonNotices({ comparison }: { comparison: ComparisonState }) {
  const { snapshot, controller } = comparison;
  const { undoRevision, undoToastId } = snapshot;
  // 적용마다 고유한 토스트 식별자에 묶는다. 같은 계정에서 새 계획이 revision을 다시 써도 이전 되돌리기의 늦은 종료가 새 토스트를 닫지 못한다.
  const dismissUndo = useCallback(() => controller.dismissUndo(undoRevision ?? undefined, undoToastId), [controller, undoRevision, undoToastId]);
  const feedbackUndo = useCallback((message: string) => controller.showFeedback(message, undoToastId), [controller, undoToastId]);
  return (
    <>
      {snapshot.toast !== null && (
        <p data-testid="chat-comparison-toast" role="alert">
          {TOAST_COPY[snapshot.toast]}
        </p>
      )}
      {snapshot.feedback !== null && <p data-testid="chat-comparison-feedback">{snapshot.feedback}</p>}
      {/* 토스트마다 인스턴스를 분리한다. 이전 되돌리기의 늦은 오류가 새 토스트의 진행 중 표시를 풀지 못한다. */}
      <UndoToast key={undoToastId} appliedRevision={undoRevision} onDismiss={dismissUndo} onFeedback={feedbackUndo} />
    </>
  );
}
