import { useState } from "react";
import { planStore } from "../../api";
import { COPY } from "../../shared/copy/copy";
import { requestUndo } from "./requestUndo";

interface UndoPlanButtonProps {
  /** 결과·되돌리기 안내를 결과 화면에 띄운다 */
  onFeedback(message: string | null): void;
}

/**
 * '직전 계획으로 되돌리기' 상시 버튼(D-095, #263, 계약 C12a-8). 토스트가 사라진 뒤에도 같은 1단계 되돌리기를 한다.
 * 보일지는 부모가 P-4의 undoAvailable로 정한다. 응답별 동작은 되돌리기 토스트와 같다.
 */
export function UndoPlanButton({ onFeedback }: UndoPlanButtonProps) {
  const [submitting, setSubmitting] = useState(false);

  async function undo() {
    const sent = planStore.getState().revision;
    if (submitting || sent === null) return;
    setSubmitting(true);
    onFeedback(null);
    const outcome = await requestUndo(sent);
    setSubmitting(false);
    switch (outcome.kind) {
      case "undone":
        planStore.acceptRevision(outcome.revision);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return;
      case "nothingToUndo":
        onFeedback(COPY["undo.nothingToUndo"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return;
      case "planChanged":
        onFeedback(COPY["plan.changedElsewhere"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return;
      case "noPlan":
        planStore.acceptNoPlan(sent);
        return;
      case "unauthenticated":
        return;
      case "failed":
        onFeedback(COPY["undo.failed"]);
    }
  }

  return (
    <button
      type="button"
      onClick={() => void undo()}
      disabled={submitting}
      aria-busy={submitting || undefined}
      className="rounded-lg px-3 py-1.5 font-semibold text-primary hover:bg-surface-muted disabled:text-text-muted"
    >
      {COPY["undo.planButton"]}
    </button>
  );
}
