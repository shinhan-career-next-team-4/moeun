import { useEffect, useState } from "react";
import { api, planStore } from "../../api";
import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";

interface UndoToastProps {
  /** 직전 적용 응답의 revision. null이면 토스트를 띄우지 않는다. */
  appliedRevision: number | null;
  onDismiss: () => void;
  onFeedback?: (message: string) => void;
}

const UNDO_TIMEOUT_MS = 10_000;

/** 되돌리기 토스트(#51, C12a-8, D-041). 10초 유지되며 revision이 달라지면 닫힌다. */
export function UndoToast({ appliedRevision, onDismiss, onFeedback }: UndoToastProps) {
  const [submittingRevision, setSubmittingRevision] = useState<number | null>(null);
  const [errorState, setErrorState] = useState<{ revision: number; text: string } | null>(null);

  useEffect(() => {
    if (appliedRevision === null) return;
    const timer = window.setTimeout(onDismiss, UNDO_TIMEOUT_MS);
    const unsub = planStore.onRevisionChange((next) => {
      if (next !== appliedRevision) onDismiss();
    });
    return () => {
      window.clearTimeout(timer);
      unsub();
    };
  }, [appliedRevision, onDismiss]);

  if (appliedRevision === null) return null;

  const submitting = submittingRevision === appliedRevision;
  const errorText = errorState?.revision === appliedRevision ? errorState.text : null;

  async function handleUndo() {
    if (submitting || appliedRevision === null) return;
    setSubmittingRevision(appliedRevision);
    setErrorState(null);
    try {
      const res = await api.request<{ revision: number }>("POST", "/api/plan/undo", {
        expectedRevision: appliedRevision,
      });
      planStore.acceptRevision(res.revision);
      onDismiss();
      planStore.loadPlan({ fresh: true }).catch(() => {});
    } catch (caught) {
      setSubmittingRevision(null);
      if (!(caught instanceof ApiError)) {
        setErrorState({ revision: appliedRevision, text: COPY["undo.failed"] });
        return;
      }
      if (caught.code === "UNAUTHENTICATED") return;
      if (caught.code === "NOTHING_TO_UNDO") {
        onDismiss();
        onFeedback?.(COPY["undo.nothingToUndo"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
      } else if (caught.code === "PLAN_CHANGED") {
        onDismiss();
        onFeedback?.(COPY["plan.changedElsewhere"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
      } else if (caught.code === "NO_PLAN") {
        onDismiss();
        planStore.acceptNoPlan(appliedRevision);
      } else {
        setErrorState({ revision: appliedRevision, text: COPY["undo.failed"] });
      }
    }
  }

  return (
    <div
      role="status"
      className="fixed bottom-6 right-6 z-50 flex flex-col gap-2 rounded-xl border border-border bg-surface px-4 py-3 shadow-lg"
    >
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium text-text">조건을 적용했어요.</span>
        <Button variant="secondary" className="h-8 px-3 text-xs" loading={submitting} onClick={handleUndo}>
          되돌리기
        </Button>
      </div>
      {errorText && (
        <p role="alert" className="text-xs text-danger">
          {errorText}
        </p>
      )}
    </div>
  );
}
