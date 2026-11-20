import { useEffect, useRef, useState } from "react";
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

  // 토스트가 닫히거나 바뀌면 진행 중이던 되돌리기 요청은 폐기된 것이다. 그 늦은 응답은 새 계획·새 토스트에 영향을 주지 않는다.
  // 세대는 React 정리를 기다리지 않고 폐기 시점에 동기로 올린다. 계획 교체와 늦은 응답이 정리 전에 이어져도 막기 위해서다.
  const generation = useRef(0);

  useEffect(() => {
    if (appliedRevision === null) return;
    const discard = () => {
      generation.current += 1;
      onDismiss();
    };
    const timer = window.setTimeout(discard, UNDO_TIMEOUT_MS);
    const unsub = planStore.onRevisionChange((next) => {
      if (next !== appliedRevision) discard();
    });
    return () => {
      window.clearTimeout(timer);
      unsub();
    };
  }, [appliedRevision, onDismiss]);

  useEffect(() => {
    return () => {
      generation.current += 1;
    };
  }, [appliedRevision]);

  if (appliedRevision === null) return null;

  const submitting = submittingRevision === appliedRevision;
  const errorText = errorState?.revision === appliedRevision ? errorState.text : null;

  async function handleUndo() {
    if (submitting || appliedRevision === null) return;
    const issuedAt = generation.current;
    setSubmittingRevision(appliedRevision);
    setErrorState(null);
    try {
      const res = await api.request<{ revision: number }>("POST", "/api/plan/undo", {
        expectedRevision: appliedRevision,
      });
      if (generation.current !== issuedAt) return;
      planStore.acceptRevision(res.revision);
      onDismiss();
      planStore.loadPlan({ fresh: true }).catch(() => {});
    } catch (caught) {
      if (generation.current !== issuedAt) return;
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
