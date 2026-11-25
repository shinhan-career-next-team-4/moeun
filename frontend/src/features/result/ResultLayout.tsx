import { useCallback, useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode } from "react";
import { ChatWindow } from "../chat";
import { COPY as CHAT_COPY } from "../chat/copy";

/** 이 너비(px) 이상이면 대화 패널을 오른쪽에 고정하고, 미만이면 서랍으로 연다. tailwind의 lg와 같다. */
const DESKTOP_MIN_WIDTH_PX = 1024;

function isDesktop(): boolean {
  return typeof window.matchMedia !== "function" || window.matchMedia(`(min-width: ${DESKTOP_MIN_WIDTH_PX}px)`).matches;
}

// 열린 패널: 좁은 화면은 오른쪽 서랍(768px 미만은 전체 화면), 데스크톱은 360px 고정 열.
const PANEL_OPEN =
  "fixed inset-y-0 right-0 z-40 flex min-h-0 w-full flex-col border-l border-border bg-surface shadow-xl md:w-[400px] lg:relative lg:z-auto lg:w-[var(--chat-panel-width)] lg:shrink-0 lg:shadow-none";
const PANEL_CLOSED = "hidden";

/** 대화 패널 너비(px, 데스크톱). 끌어서 바꾸고 브라우저에 기억한다(#362). */
const PANEL_WIDTH_DEFAULT_PX = 360;
const PANEL_WIDTH_MIN_PX = 280;
const PANEL_WIDTH_MAX_PX = 720;
const PANEL_WIDTH_STEP_PX = 16;
const STORAGE_KEY = "moeun.chatPanel";

type PanelSide = "left" | "right";
interface PanelPrefs {
  side: PanelSide;
  width: number;
}

const clampWidth = (width: number): number => {
  // 본문이 너무 좁아지지 않게 화면의 60%까지만 늘린다
  const max = Math.max(PANEL_WIDTH_MIN_PX, Math.min(PANEL_WIDTH_MAX_PX, Math.floor(window.innerWidth * 0.6)));
  return Math.min(max, Math.max(PANEL_WIDTH_MIN_PX, Math.round(width)));
};

function loadPrefs(): PanelPrefs {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "null") as Partial<PanelPrefs> | null;
    const side: PanelSide = parsed?.side === "left" ? "left" : "right";
    const width = typeof parsed?.width === "number" ? clampWidth(parsed.width) : PANEL_WIDTH_DEFAULT_PX;
    return { side, width };
  } catch {
    return { side: "right", width: PANEL_WIDTH_DEFAULT_PX };
  }
}

function savePrefs(prefs: PanelPrefs): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // 저장이 막힌 브라우저에서는 이번 방문 동안만 유지한다
  }
}

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
  const [prefs, setPrefs] = useState<PanelPrefs>(loadPrefs);
  const [dragging, setDragging] = useState(false);
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

  function changePrefs(next: PanelPrefs) {
    setPrefs(next);
    savePrefs(next);
  }

  /** 안쪽 가장자리를 끌 때의 너비: 오른쪽 패널은 패널 오른쪽 끝에서 포인터까지, 왼쪽 패널은 왼쪽 끝에서 포인터까지다. */
  function widthAt(clientX: number): number {
    const rect = panelRef.current?.getBoundingClientRect();
    if (!rect) return prefs.width;
    return clampWidth(prefs.side === "right" ? rect.right - clientX : clientX - rect.left);
  }

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
      <div className={`flex min-h-0 flex-1 ${prefs.side === "left" ? "lg:flex-row-reverse" : ""}`}>
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
          style={{ "--chat-panel-width": `${prefs.width}px` } as CSSProperties}
          className={`${open ? PANEL_OPEN : PANEL_CLOSED} ${prefs.side === "left" ? "lg:border-l-0 lg:border-r" : ""} ${dragging ? "lg:select-none" : ""}`}
        >
          {open && (
            <div
              role="separator"
              aria-orientation="vertical"
              aria-label={CHAT_COPY.panelResize}
              aria-valuenow={prefs.width}
              aria-valuemin={PANEL_WIDTH_MIN_PX}
              aria-valuemax={PANEL_WIDTH_MAX_PX}
              tabIndex={0}
              data-testid="result-panel-resize"
              onPointerDown={(event) => {
                event.currentTarget.setPointerCapture(event.pointerId);
                setDragging(true);
              }}
              onPointerMove={(event) => {
                if (dragging) setPrefs((current) => ({ ...current, width: widthAt(event.clientX) }));
              }}
              onPointerUp={() => {
                setDragging(false);
                savePrefs(prefs);
              }}
              onKeyDown={(event) => {
                const grow = prefs.side === "right" ? "ArrowLeft" : "ArrowRight";
                const shrink = prefs.side === "right" ? "ArrowRight" : "ArrowLeft";
                if (event.key !== grow && event.key !== shrink) return;
                event.preventDefault();
                const delta = event.key === grow ? PANEL_WIDTH_STEP_PX : -PANEL_WIDTH_STEP_PX;
                changePrefs({ ...prefs, width: clampWidth(prefs.width + delta) });
              }}
              className={`absolute inset-y-0 z-10 hidden w-1.5 cursor-col-resize hover:bg-primary/30 focus-visible:bg-primary/40 focus-visible:outline-none lg:block ${
                prefs.side === "right" ? "-left-0.5" : "-right-0.5"
              } ${dragging ? "bg-primary/40" : ""}`}
            />
          )}
          <ChatWindow
            visible={open}
            onAssistantMessage={showUnread}
            titleAction={
              <button
                type="button"
                data-testid="result-panel-side"
                aria-label={prefs.side === "right" ? CHAT_COPY.panelMoveLeft : CHAT_COPY.panelMoveRight}
                title={prefs.side === "right" ? CHAT_COPY.panelMoveLeft : CHAT_COPY.panelMoveRight}
                onClick={() => changePrefs({ ...prefs, side: prefs.side === "right" ? "left" : "right" })}
                className="hidden rounded-lg px-1.5 py-0.5 text-sm leading-none text-text-muted hover:bg-surface-muted lg:inline-block"
              >
                {prefs.side === "right" ? "⇤" : "⇥"}
              </button>
            }
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
