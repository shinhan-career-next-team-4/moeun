// F-2 폴링 규칙(H-11, C12b-5)을 React 밖에서 돌리는 제어기. 훅이 상태 변화를 구독한다.
import { ApiError } from "../../api/errors";
import type { ChatDeps } from "./index";
import type { ChatMessage, ChatPage, ChatPollingState } from "./types";

export const RESTORE_PATH = "/api/chat/messages";
export const POLL_INTERVAL_MS = 1000;
const MAX_FAILURES = 3;

export interface PollingController {
  /** 폴링을 새 구간으로 시작한다(실패 횟수·안전장치 사용 표시·실패 안내를 지운다). 첫 호출은 delay 뒤. */
  begin(delayMs: number): void;
  /** F-1 202: 보낸 질문과 대기 중인 답을 로컬로 그리고 폴링을 재개한다. */
  accepted(userMessage: ChatMessage, pendingMessage: ChatMessage): void;
  /** F-1 409: 다른 곳에서 만든 대기가 있다. 보내기를 막고 폴링을 재개한다. */
  answerPendingElsewhere(): void;
  dispose(): void;
}

const INITIAL: ChatPollingState = { messages: [], hasPending: false, answerPending: false, pollingFailed: false };

export function createPollingController(
  deps: Pick<ChatDeps, "api" | "polling">,
  publish: (state: ChatPollingState) => void,
): PollingController {
  const byId = new Map<number, ChatMessage>();
  let state = INITIAL;
  let disposed = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let epoch = 0;
  let failures = 0;
  let safetyUsed = false;
  // 전체 복원(afterId 없는 F-2)이 필요한 상태. 요구(demand)가 처리(served)보다 크면 필요하다. 처음엔 필요하고, 안전장치는 요구를 올린다.
  // 복원 응답은 그 호출을 보낼 때의 요구까지만 처리한다. 모순을 발견하기 전에 보낸 복원은 나중에 올라간 요구를 풀지 못한다.
  // 처리 표시는 전체 복원 응답을 반영할 때만 올린다.
  let restoreDemand = 1;
  let restoreServed = 0;
  let incrementalFirst = false; // 새 구간의 첫 호출은 가진 메시지 뒤를 먼저 잇고, 복원은 그다음 호출에서 받는다.
  const restoresInFlight: number[] = []; // 응답을 기다리는 전체 복원이 보낼 때 가진 요구. 지금 요구를 덮는 복원이 있으면 새 복원을 내지 않는다.
  let stopped = false;
  let activeCall: number | null = null;
  let unregister: (() => void) | null = null;

  function emit(patch: Partial<ChatPollingState>): void {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  }

  const needsRestore = () => restoreServed < restoreDemand;
  const restoreCovered = () => restoresInFlight.some((demand) => demand >= restoreDemand);
  const sortedMessages = () => [...byId.values()].sort((a, b) => a.id - b.id);
  const heldPendingIds = () => sortedMessages().filter((m) => m.status === "PENDING").map((m) => m.id);

  // 진행 중인 요청의 응답은 epoch가 달라져 버려진다.
  function halt(): void {
    epoch += 1;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    unregister?.();
    unregister = null;
  }

  function nextPath(): string {
    const incremental = incrementalFirst;
    incrementalFirst = false;
    if ((needsRestore() && !incremental && !restoreCovered()) || byId.size === 0) return RESTORE_PATH;
    const pending = heldPendingIds();
    const afterId = pending.length > 0 ? pending[0] - 1 : sortedMessages().at(-1)!.id;
    return `${RESTORE_PATH}?afterId=${afterId}`;
  }

  function schedule(delayMs: number): void {
    const mine = epoch;
    timer = setTimeout(() => {
      timer = null;
      void poll(mine);
    }, delayMs);
  }

  // 래퍼의 stopAll()(401)이 부르는 중단. 멈춘 사유를 남겨 늦게 온 응답이 폴링을 되살리지 않게 한다.
  function stopByAuth(): void {
    stopped = true;
    halt();
  }

  // 멈춰 있던 폴링을 현재 구간 그대로 다시 돌린다(실패 횟수·안전장치 표시는 유지).
  function arm(delayMs: number): void {
    if (unregister === null) unregister = deps.polling.register(stopByAuth);
    schedule(delayMs);
  }

  // 현재 구간이 멈춰 있고 이 구간에서 다시 요청할 수 있는 상태인가.
  const isIdle = () => !disposed && !stopped && !state.pollingFailed && timer === null && activeCall !== epoch;

  async function poll(mine: number): Promise<void> {
    const path = nextPath();
    const isRestore = path === RESTORE_PATH;
    let page: ChatPage;
    activeCall = mine;
    const demand = restoreDemand;
    if (isRestore) restoresInFlight.push(demand);
    try {
      page = await deps.api.request<ChatPage>("GET", path);
    } catch (error) {
      if (isRestore) restoresInFlight.splice(restoresInFlight.indexOf(demand), 1);
      if (activeCall === mine) activeCall = null;
      if (mine !== epoch) {
        // 새 구간(F-1 202 등)이 먼저 시작돼 복원 응답을 못 받았다. 복원 필요 상태는 그대로이니 다음 호출은 복원으로 간다.
        if (isRestore && !disposed && !stopped && !(error instanceof ApiError && error.code === "UNAUTHENTICATED")) {
          incrementalFirst = false;
          // 새 구간이 이미 끝났고 복원 요구가 아직 남아 있다면 다음 호출이 없으니 복원을 직접 예약한다. 다른 복원이 요구를 이미 풀었으면 끝난 채로 둔다.
          if (isIdle() && needsRestore() && !restoreCovered()) arm(POLL_INTERVAL_MS);
        }
        return;
      }
      // 401은 래퍼가 로그인으로 보낸다. 실패 안내 없이 멈추기만 한다.
      if (error instanceof ApiError && error.code === "UNAUTHENTICATED") {
        stopByAuth();
        return;
      }
      failures += 1;
      if (failures >= MAX_FAILURES) {
        halt();
        emit({ pollingFailed: true });
        return;
      }
      schedule(POLL_INTERVAL_MS);
      return;
    }
    if (isRestore) restoresInFlight.splice(restoresInFlight.indexOf(demand), 1);
    if (activeCall === mine) activeCall = null;
    if (mine !== epoch) {
      // 복원 응답이 새 구간보다 늦게 왔다. 이전 대화만 보태고(이미 가진 id는 더 새로울 수 있어 두지 않는다) 구간 상태는 건드리지 않는다.
      if (isRestore && !disposed) {
        let added = false;
        page.messages.forEach((message) => {
          if (!byId.has(message.id)) {
            byId.set(message.id, message);
            added = true;
          }
        });
        restoreServed = Math.max(restoreServed, demand);
        if (added) emit({ messages: sortedMessages() });
        // 새 구간이 이미 끝났는데 합친 뒤 서버 값과 가진 PENDING이 어긋나면 안전장치 복원을 한 번 예약한다.
        if (isIdle() && state.hasPending !== (heldPendingIds().length > 0) && !safetyUsed) {
          safetyUsed = true;
          restoreDemand += 1;
          arm(POLL_INTERVAL_MS);
        }
      }
      return;
    }

    failures = 0;
    page.messages.forEach((message) => byId.set(message.id, message));
    if (isRestore) restoreServed = Math.max(restoreServed, demand);
    emit({ messages: sortedMessages(), hasPending: page.hasPending, answerPending: page.answerPending });

    // 서버의 hasPending과 가진 PENDING이 어긋나면 복원을 한 구간에 한 번만 부른다.
    if (page.hasPending !== (heldPendingIds().length > 0) && !safetyUsed) {
      safetyUsed = true;
      restoreDemand += 1;
      schedule(POLL_INTERVAL_MS);
      return;
    }
    if (page.hasPending || (needsRestore() && !restoreCovered())) schedule(POLL_INTERVAL_MS);
    else halt();
  }

  function begin(delayMs: number): void {
    if (disposed) return;
    halt();
    failures = 0;
    safetyUsed = false;
    incrementalFirst = true;
    stopped = false;
    if (state.pollingFailed) emit({ pollingFailed: false });
    unregister = deps.polling.register(stopByAuth);
    schedule(delayMs);
  }

  return {
    begin,
    accepted(userMessage, pendingMessage) {
      byId.set(userMessage.id, userMessage);
      byId.set(pendingMessage.id, pendingMessage);
      emit({ messages: sortedMessages(), answerPending: true });
      begin(POLL_INTERVAL_MS);
    },
    answerPendingElsewhere() {
      emit({ answerPending: true });
      begin(POLL_INTERVAL_MS);
    },
    dispose() {
      disposed = true;
      halt();
    },
  };
}
