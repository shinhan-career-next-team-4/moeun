// 비교 카드 제어기(C12b-6, C12b-7). F-4 호출·적용 버튼 활성 판정·F-5 적용 흐름을 React 밖에서 돌리고, 훅이 상태를 읽는다.
// 공유 계획 store는 revision 칸만 구독해 읽는다(D-069). 되돌리기·입력 저장은 A팀 코드라 부르지 않는다.
import { ApiError } from "../../../api/errors";
import type { PlanStore } from "../../../api/planStore";
import type { ChatMessage } from "../types";
import type { ToastKey } from "./copy";
import { parseComparison, type Comparison } from "./types";

export type ComparisonPlanStore = Pick<PlanStore, "getState" | "subscribe" | "acceptRevision" | "loadPlan" | "acceptNoPlan">;

export interface ComparisonDeps {
  api: { request<T = unknown>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> };
  planStore: ComparisonPlanStore;
}

/** loading: 응답 전(그리지 않는다). empty: 404·400·401(자리를 비운다). failed: 그 밖의 실패(안내 문구). */
export type ComparisonEntry = { kind: "loading" } | { kind: "empty" } | { kind: "failed" } | { kind: "ready"; data: Comparison };

export interface ComparisonSnapshot {
  revision: number | null;
  entries: ReadonlyMap<number, ComparisonEntry>;
  /** revision이 바뀐 시점에 이미 있던 비교. 늦게 활성이 되지 않는다(P-8). */
  stale: ReadonlySet<number>;
  /** 적용 오류로 꺼진 버튼(그 메시지만). */
  off: ReadonlySet<number>;
  /** 적용 요청 중인 메시지. */
  busy: ReadonlySet<number>;
  toast: ToastKey | null;
  /** 되돌리기 토스트가 들고 있는 revision. null이면 토스트가 없다. */
  undoRevision: number | null;
  /** 적용 성공마다 하나씩 늘어나는 토스트 식별자. revision이 같아도 이전 토스트의 콜백은 새 토스트에 닿지 않는다(P-74). */
  undoToastId: number;
  feedback: string | null;
}

export interface ComparisonController {
  getSnapshot(): ComparisonSnapshot;
  listen(listener: () => void): () => void;
  /**
   * 폴링이 메시지를 내놓는 그 순간에 부른다. 렌더를 기다리지 않아 revision 변경과의 실제 순서가 남는다:
   * 변경 전에 받은 비교는 비활성이고, 변경 뒤에 받은 비교(P-29)는 F-4 applicable을 따른다.
   */
  receive(messages: ChatMessage[]): void;
  /** 가진 메시지를 알린다. 가장 최근 RECALC_RESULT의 F-4를 한 번만 부른다. */
  sync(messages: ChatMessage[]): void;
  apply(messageId: number): void;
  /** revision·toastId를 주면 토스트가 아직 그 값을 들고 있을 때만 닫는다(이전 되돌리기의 늦은 종료가 새 토스트를 닫지 않게). */
  dismissUndo(revision?: number, toastId?: number): void;
  /** toastId를 주면 그 토스트가 가장 최근에 띄운 것일 때만 보인다. */
  showFeedback(message: string, toastId?: number): void;
}

// 되돌리기 토스트가 보이는 시간(C12a-8, D-041).
const UNDO_VISIBLE_MS = 10_000;

const EMPTY_CODES = new Set(["NOT_FOUND", "MALFORMED_REQUEST", "UNAUTHENTICATED"]);

const withValue = <T>(set: ReadonlySet<T>, value: T): ReadonlySet<T> => new Set([...set, value]);
const withoutValue = <T>(set: ReadonlySet<T>, value: T): ReadonlySet<T> => new Set([...set].filter((item) => item !== value));

const isRecalcResult = (message: ChatMessage) => message.messageType === "RECALC_RESULT" && message.status === "COMPLETED";

/** 적용 버튼이 활성인가. latestId는 지금 가진 메시지 중 가장 큰 RECALC_RESULT id다(P-1). */
export function isApplyActive(snapshot: ComparisonSnapshot, latestId: number | null, messageId: number): boolean {
  const entry = snapshot.entries.get(messageId);
  return (
    latestId === messageId &&
    entry?.kind === "ready" &&
    entry.data.applicable &&
    snapshot.revision !== null &&
    !snapshot.stale.has(messageId) &&
    !snapshot.off.has(messageId) &&
    !snapshot.busy.has(messageId)
  );
}

function appliedRevisionOf(body: unknown): number | null {
  const revision = typeof body === "object" && body !== null ? (body as { revision?: unknown }).revision : undefined;
  return typeof revision === "number" && Number.isSafeInteger(revision) && revision > 0 ? revision : null;
}

export function createComparisonController(deps: ComparisonDeps): ComparisonController {
  const { planStore: store } = deps;
  let snapshot: ComparisonSnapshot = {
    revision: store.getState().revision,
    entries: new Map(),
    stale: new Set(),
    off: new Set(),
    busy: new Set(),
    toast: null,
    undoRevision: null,
    undoToastId: 0,
    feedback: null,
  };
  // 지금까지 받은 완료된 RECALC_RESULT id. 메시지는 지워지지 않아 합집합으로만 늘린다.
  let recalcIds: ReadonlySet<number> = new Set();
  // 값이 비워진 횟수. 요청 중에 비워졌다 다시 채워진 store에는 이전 적용 성공을 반영하지 않는다.
  let clearedCount = 0;
  let attempts = 0;
  // 토스트 만료 시각은 적용 성공을 반영하는 그 순간에 정한다. 렌더·재조회 지연에 밀리지 않게 제어기가 직접 센다(P-73).
  let undoTimer: ReturnType<typeof setTimeout> | null = null;
  const listeners = new Set<() => void>();
  let disconnect: (() => void) | null = null;

  function clearUndoTimer(): void {
    if (undoTimer !== null) clearTimeout(undoTimer);
    undoTimer = null;
  }

  function update(patch: Partial<ComparisonSnapshot>): void {
    snapshot = { ...snapshot, ...patch };
    [...listeners].forEach((listener) => listener());
  }

  // revision 칸이 바뀌면 그때 가진 비교를 모두 끈다. 최초 로드(null -> N)는 제외하고, N -> null은 바뀜이다(P-7, P-46).
  function onRevision(next: number | null): void {
    if (next === snapshot.revision) return;
    const patch: Partial<ComparisonSnapshot> = { revision: next };
    if (snapshot.revision !== null) {
      patch.stale = new Set([...snapshot.stale, ...recalcIds]);
    }
    if (next === null) clearedCount += 1;
    if (snapshot.undoRevision !== null && next !== snapshot.undoRevision) {
      patch.undoRevision = null;
      clearUndoTimer();
    }
    update(patch);
  }

  // store 구독은 이 한 곳에서만 만든다. 대화창 구독자와 진행 중인 적용 요청이 각자 붙잡고, 모두 놓으면 끊는다.
  // 적용 요청이 붙잡고 있어야 대화창이 사라진 사이의 store 초기화·revision 변경도 요청이 끝날 때 알 수 있다.
  let holders = 0;
  function retain(): () => void {
    holders += 1;
    if (disconnect === null) {
      disconnect = store.subscribe((state) => onRevision(state.revision));
      onRevision(store.getState().revision);
    }
    let held = true;
    return () => {
      if (!held) return;
      held = false;
      holders -= 1;
      if (holders === 0) {
        disconnect?.();
        disconnect = null;
      }
    };
  }

  function listen(listener: () => void): () => void {
    listeners.add(listener);
    const release = retain();
    return () => {
      listeners.delete(listener);
      release();
    };
  }

  function setEntry(messageId: number, entry: ComparisonEntry): void {
    update({ entries: new Map([...snapshot.entries, [messageId, entry]]) });
  }

  async function load(messageId: number): Promise<void> {
    setEntry(messageId, { kind: "loading" });
    try {
      const body = await deps.api.request<unknown>("GET", `/api/chat/messages/${messageId}/comparison`);
      const data = parseComparison(body);
      setEntry(messageId, data ? { kind: "ready", data } : { kind: "failed" });
    } catch (error) {
      const code = error instanceof ApiError ? error.code : null;
      setEntry(messageId, { kind: code !== null && EMPTY_CODES.has(code) ? "empty" : "failed" });
    }
  }

  const recalcIdsOf = (messages: ChatMessage[]) => messages.filter(isRecalcResult).map((message) => message.id).sort((a, b) => a - b);

  function receive(messages: ChatMessage[]): void {
    const ids = recalcIdsOf(messages);
    if (!ids.some((id) => !recalcIds.has(id))) return;
    recalcIds = new Set([...recalcIds, ...ids]);
    // 새 비교가 도착한 그 순간에만 F-5 오류 안내를 지운다. 그 뒤 받은 안내는 같은 렌더의 뒤늦은 sync가 지우지 않는다(P-33, P-52).
    if (snapshot.toast !== null) update({ toast: null });
  }

  function sync(messages: ChatMessage[]): void {
    receive(messages);
    const ids = recalcIdsOf(messages);
    const latest = ids[ids.length - 1];
    if (latest !== undefined && !snapshot.entries.has(latest)) void load(latest);
  }

  // 재조회 실패·시작 실패는 대화창 밖으로 새지 않는다. 결과 화면의 실패 표시는 결과 화면 몫이다(P-53).
  function reloadPlan(): void {
    try {
      Promise.resolve(store.loadPlan()).catch(() => {});
    } catch {
      // 재조회를 시작하지 못해도 적용 결과는 그대로다.
    }
  }

  function showUndoToast(revision: number): void {
    clearUndoTimer();
    const toastId = snapshot.undoToastId + 1;
    update({ undoRevision: revision, undoToastId: toastId });
    undoTimer = setTimeout(() => dismissUndo(revision, toastId), UNDO_VISIBLE_MS);
  }

  function dismissUndo(revision?: number, toastId?: number): void {
    if (snapshot.undoRevision === null) return;
    if (revision !== undefined && revision !== snapshot.undoRevision) return;
    if (toastId !== undefined && toastId !== snapshot.undoToastId) return;
    clearUndoTimer();
    update({ undoRevision: null });
  }

  function onApplied(revision: number, clearedAtSend: number): void {
    if (clearedCount !== clearedAtSend || store.getState().revision === null) return; // 요청 중에 비워졌다(NO_PLAN). 다시 채우지 않는다.
    store.acceptRevision(revision);
    if (store.getState().revision === revision) showUndoToast(revision);
    reloadPlan();
  }

  function onApplyFailed(messageId: number, attempt: number, sent: number, error: unknown): void {
    const code = error instanceof ApiError ? error.code : null;
    // 더 늦게 시작한 적용이 있으면 이 오류 안내는 그 결과를 덮지 않는다(P-52). 버튼·재조회는 그대로 한다.
    const show = (key: ToastKey) => {
      if (attempt === attempts) update({ toast: key });
    };
    const turnOff = () => update({ off: withValue(snapshot.off, messageId) });
    switch (code) {
      case "UNAUTHENTICATED":
        return; // 로그인 이동은 래퍼가 한다
      case "STALE_COMPARISON":
        turnOff();
        show("applyStaleComparison");
        return;
      case "VALIDATION_FAILED":
      case "FIELD_NOT_CHANGEABLE":
        turnOff();
        show("applyNotApplicable");
        return;
      case "NOT_FOUND":
        turnOff();
        return;
      case "PLAN_CHANGED":
        show("applyPlanChanged");
        reloadPlan();
        return;
      case "NO_PLAN":
        store.acceptNoPlan(sent);
        return;
      default:
        show("applyFailed");
    }
  }

  async function apply(messageId: number): Promise<void> {
    const latest = recalcIds.size > 0 ? Math.max(...recalcIds) : null;
    if (!isApplyActive(snapshot, latest, messageId)) return;
    const sent = store.getState().revision;
    if (sent === null) return;
    const release = retain();
    const clearedAtSend = clearedCount;
    attempts += 1;
    const attempt = attempts;
    update({ busy: withValue(snapshot.busy, messageId), toast: null, feedback: null });
    let revision: number | null = null;
    let failure: unknown = null;
    try {
      const body = await deps.api.request<unknown>("POST", `/api/chat/messages/${messageId}/apply`, { expectedRevision: sent });
      revision = appliedRevisionOf(body);
      if (revision === null) failure = ApiError.network(); // 200이어도 유효한 revision이 없으면 성공이 아니다(P-58)
    } catch (error) {
      failure = error;
    }
    update({ busy: withoutValue(snapshot.busy, messageId) });
    try {
      if (revision !== null) onApplied(revision, clearedAtSend);
      else onApplyFailed(messageId, attempt, sent, failure);
    } finally {
      release();
    }
  }

  return {
    getSnapshot: () => snapshot,
    listen,
    receive,
    sync,
    apply: (messageId) => void apply(messageId),
    dismissUndo,
    showFeedback(message, toastId) {
      if (toastId === undefined || toastId === snapshot.undoToastId) update({ feedback: message });
    },
  };
}
