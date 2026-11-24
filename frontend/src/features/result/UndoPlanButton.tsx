import { useEffect, useState, useSyncExternalStore } from "react";
import { planStore } from "../../api";
import { COPY } from "../../shared/copy/copy";
import { requestUndo } from "./requestUndo";

/** undo-sparkle 애니메이션 길이(globals.css)와 맞춘다. */
const SPARKLE_MS = 1500;

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
  // 계획이 바뀌면(적용 등) 버튼이 한 번 반짝여 되돌릴 수 있음을 알린다(#360). 처음 그려질 때는 반짝이지 않는다
  const revision = useSyncExternalStore(planStore.subscribe, () => planStore.getState().revision);
  const [seenRevision, setSeenRevision] = useState(revision);
  const [sparkling, setSparkling] = useState(false);
  // 애니메이션(1.4초)이 끝날 즈음 클래스를 뗀다. 다음 변경에서 다시 반짝이려면 떼어 두어야 한다
  useEffect(() => {
    if (!sparkling) return;
    const timer = window.setTimeout(() => setSparkling(false), SPARKLE_MS);
    return () => window.clearTimeout(timer);
  }, [sparkling]);
  if (revision !== seenRevision) {
    setSeenRevision(revision);
    if (revision !== null && seenRevision !== null && !submitting) setSparkling(true);
  }

  async function undo() {
    const sent = planStore.getState().revision;
    if (submitting || sent === null) return;
    setSubmitting(true);
    onFeedback(null);
    const outcome = await requestUndo(sent);
    // 요청 중에 계획 상태가 비워지거나(다른 계정 로그인) 바뀌었으면 이 응답은 지금 계획의 것이 아니다. 버린다
    if (planStore.getState().revision !== sent) {
      setSubmitting(false);
      return;
    }
    if (outcome.kind !== "undone") setSubmitting(false);
    switch (outcome.kind) {
      case "undone":
        // 재조회가 끝나 버튼이 사라질 때까지 막아 둔다. 그 사이 다시 누르면 '이미 되돌렸어요'가 뜬다
        planStore.acceptRevision(outcome.revision);
        planStore.loadPlan({ fresh: true }).catch(() => setSubmitting(false));
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
      className={`rounded-lg px-3 py-1.5 font-semibold text-primary hover:bg-surface-muted disabled:text-text-muted ${
        sparkling ? "undo-sparkle" : ""
      }`}
    >
      {COPY["undo.planButton"]}
    </button>
  );
}
