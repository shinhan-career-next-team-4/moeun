import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { ChatWindow } from "../chat";
import { COPY as CHAT_COPY } from "../chat/copy";

/** 이 너비(px) 이상이면 대화 패널을 오른쪽에 고정하고, 미만이면 서랍으로 연다. tailwind의 lg와 같다. */
const DESKTOP_MIN_WIDTH_PX = 1024;

function isDesktop(): boolean {
  return typeof window.matchMedia !== "function" || window.matchMedia(`(min-width: ${DESKTOP_MIN_WIDTH_PX}px)`).matches;
}

// 열린 패널: 좁은 화면은 오른쪽 서랍(768px 미만은 전체 화면), 데스크톱은 360px 고정 열.
const PANEL_OPEN =
  "fixed inset-y-0 right-0 z-40 flex min-h-0 w-full flex-col border-l border-border bg-surface shadow-xl md:w-[400px] lg:static lg:z-auto lg:w-[360px] lg:shrink-0 lg:shadow-none";
const PANEL_CLOSED = "hidden";

interface ResultLayoutProps {
  /** 헤더 아래에 고정되는 조건 바 */
  bar: ReactNode;
  /** 왼쪽 본문. 데스크톱에서는 패널과 따로 스크롤된다 */
  children: ReactNode;
}

/**
 * 결과 화면 틀(#224). 조건 바는 고정, 본문과 대화 패널은 데스크톱에서 각자 스크롤한다.
 * 좁은 화면은 일반 페이지 스크롤이고 패널은 서랍이다. 서랍이 닫혀 있어도 대화창은 계속 떠 있어서
 * 설명이 도착하면 "AI 설명" 단추에 점이 보인다. 열림 상태는 저장하지 않고, 화면이 처음 뜰 때의 너비로만 정한다.
 */
export function ResultLayout({ bar, children }: ResultLayoutProps) {
  const [open, setOpen] = useState(isDesktop);
  const [unread, setUnread] = useState(false);
  const openButtonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  // 사용자가 단추·Esc로 바꾼 뒤에만 포커스를 옮긴다(처음 그릴 때 포커스를 빼앗지 않는다).
  const focusAfterToggleRef = useRef(false);

  const showUnread = useCallback(() => setUnread(true), []);

  function toggle(next: boolean) {
    focusAfterToggleRef.current = true;
    setOpen(next);
    setUnread(false);
  }

  useEffect(() => {
    if (!focusAfterToggleRef.current) return;
    focusAfterToggleRef.current = false;
    (open ? panelRef.current : openButtonRef.current)?.focus();
  }, [open]);

  function closeOnEscape(event: KeyboardEvent) {
    if (event.key === "Escape") toggle(false);
  }

  return (
    <div className="flex flex-col lg:h-[calc(100dvh-3.5rem)]">
      <div className="flex shrink-0 items-center gap-3 border-b border-border bg-surface px-6 py-3">
        <div className="min-w-0 flex-1">{bar}</div>
        {!open && (
          <button
            ref={openButtonRef}
            type="button"
            data-testid="result-panel-open"
            onClick={() => toggle(true)}
            className="relative shrink-0 rounded-lg border border-border bg-surface px-3 py-1.5 text-sm font-semibold text-primary hover:bg-surface-muted"
          >
            {CHAT_COPY.panelOpen}
            {unread && (
              <span
                data-testid="result-panel-unread"
                role="img"
                aria-label={CHAT_COPY.panelUnread}
                className="absolute -right-1 -top-1 size-2.5 rounded-full bg-danger"
              />
            )}
          </button>
        )}
      </div>
      <div className="flex min-h-0 flex-1">
        <div className="min-w-0 flex-1 lg:overflow-y-auto">{children}</div>
        {open && (
          <button
            type="button"
            tabIndex={-1}
            aria-hidden="true"
            data-testid="result-panel-backdrop"
            onClick={() => toggle(false)}
            className="fixed inset-0 z-30 bg-black/30 lg:hidden"
          />
        )}
        <aside
          ref={panelRef}
          tabIndex={-1}
          data-testid="result-panel"
          onKeyDown={closeOnEscape}
          className={open ? PANEL_OPEN : PANEL_CLOSED}
        >
          <ChatWindow
            visible={open}
            onAssistantMessage={showUnread}
            headerAction={
              <button
                type="button"
                data-testid="result-panel-close"
                aria-label={CHAT_COPY.panelClose}
                onClick={() => toggle(false)}
                className="rounded-lg px-2 py-1 text-base leading-none text-text-muted hover:bg-surface-muted"
              >
                ×
              </button>
            }
          />
        </aside>
      </div>
    </div>
  );
}
