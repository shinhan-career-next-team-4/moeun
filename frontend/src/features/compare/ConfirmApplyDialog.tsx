import { useEffect, useId, useRef, useState } from "react";
import { COPY, type CopyMap } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import type { ComparisonView } from "./types";

interface ConfirmApplyDialogProps {
  comparison: ComparisonView;
  /** 적용 요청 중. 두 버튼을 막는다(중복 클릭) */
  applying?: boolean;
  /** 창을 연 채로 보여 줄 실패 안내(재시도 가능한 실패) */
  error?: string | null;
  onCancel(): void;
  onConfirm(): void;
}

/** 바뀌는 조건 한 줄. 값은 비교 응답의 display 문자열 그대로다(NFR-INT-01) */
function changeText(display: Record<string, string>, key: string): string | null {
  const current = display[`conditions.current.${key}`];
  const changed = display[`conditions.changed.${key}`];
  if (!changed) return null;
  if (!current) return changed;
  return COPY["compare.confirmChange"].replace("{current}", current).replace("{changed}", changed);
}

/**
 * 비교 결과를 내 계획에 적용하기 전 확인창(FR-PLAN-01·02, #68, 계약 C12a-9-2).
 * 떠 있는 동안 내 계획은 그대로이고, 취소하면 아무 요청도 보내지 않는다.
 */
export function ConfirmApplyDialog({ comparison, applying = false, error = null, onCancel, onConfirm }: ConfirmApplyDialogProps) {
  const copy: CopyMap = COPY;
  const titleId = useId();
  const listId = useId();
  const noticeId = useId();

  const cancelRef = useRef<HTMLButtonElement>(null);
  // 창을 연 버튼은 렌더 중에 기억한다. 커밋되면 배경이 inert가 되어 브라우저가 그 버튼의 포커스를 먼저 풀기 때문이다
  const [opener] = useState(() => (document.activeElement instanceof HTMLElement ? document.activeElement : null));

  // 열리면 취소에 포커스를 두고, 닫히면 창을 연 버튼으로 돌려준다. 배경의 inert가 풀린 뒤라 포커스를 받을 수 있다
  useEffect(() => {
    cancelRef.current?.focus();
    return () => {
      if (opener?.isConnected) opener.focus();
    };
  }, [opener]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !applying) onCancel();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [applying, onCancel]);

  const rows = comparison.changedKeys.flatMap((key) => {
    const text = changeText(comparison.display, key);
    return text === null ? [] : [{ key, label: copy[`condition.${key}`] ?? key, text }];
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={`${listId} ${noticeId}`}
        className="flex w-full max-w-md flex-col gap-4 rounded-xl border border-border bg-surface p-6 shadow-lg"
      >
        <h2 id={titleId} className="text-base font-semibold text-text">
          {COPY["compare.confirmTitle"]}
        </h2>
        <ul id={listId} className="flex flex-col gap-2 text-sm">
          {rows.map(({ key, label, text }) => (
            <li key={key} className="flex justify-between gap-3 rounded-lg bg-surface-muted px-3 py-2">
              <span className="text-text-muted">{label}</span>
              <span className="font-medium text-text">{text}</span>
            </li>
          ))}
        </ul>
        <p id={noticeId} className="text-xs leading-relaxed text-text-muted">
          {COPY["compare.confirmNotice"]}
        </p>
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button ref={cancelRef} variant="secondary" disabled={applying} onClick={onCancel}>
            {COPY["compare.confirmCancel"]}
          </Button>
          <Button loading={applying} onClick={onConfirm}>
            {COPY["compare.confirmApply"]}
          </Button>
        </div>
      </div>
    </div>
  );
}
