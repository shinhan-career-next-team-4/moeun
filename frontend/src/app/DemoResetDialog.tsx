import { useEffect, useId, useRef } from "react";
import { COPY } from "../shared/copy/copy";
import { Button } from "../shared/ui";

interface DemoResetDialogProps {
  /** 초기화 요청 중. 두 버튼을 막는다(중복 클릭) */
  resetting?: boolean;
  /** 창을 연 채로 보여 줄 실패 안내 */
  error?: string | null;
  onCancel(): void;
  onConfirm(): void;
}

/**
 * 데모 초기화 확인 팝업. 아이디를 세 번 누르면 뜨고, 지금 로그인한 계정의 계획과 대화가 지워진다는 것을 알린다.
 * 취소하면 아무 요청도 보내지 않는다. 열리면 취소에 포커스가 있어 Enter를 잘못 눌러도 지워지지 않는다.
 */
export function DemoResetDialog({ resetting = false, error = null, onCancel, onConfirm }: DemoResetDialogProps) {
  const titleId = useId();
  const messageId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    cancelRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !resetting) onCancel();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [resetting, onCancel]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={messageId}
        className="flex w-full max-w-sm flex-col gap-4 rounded-xl border border-border bg-surface p-6 shadow-lg">
        <h2 id={titleId} className="text-base font-semibold text-text">
          {COPY["demo.reset"]}
        </h2>
        <p id={messageId} className="text-sm leading-relaxed text-text-muted">
          {COPY["demo.resetConfirm"]}
        </p>
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button ref={cancelRef} variant="secondary" disabled={resetting} onClick={onCancel}>
            {COPY["demo.resetCancel"]}
          </Button>
          <Button loading={resetting} onClick={onConfirm}>
            {COPY["demo.resetYes"]}
          </Button>
        </div>
      </div>
    </div>
  );
}
