// 결과(CALCULATED)가 store에 들어오면 AI 설명(F-3)을 한 번 요청한다(C12b-3a). F-3을 부르는 곳은 이 파일의 effect 하나다.
// 자동 설명은 처음 진입 때 한 번뿐이다(#280). 왼쪽 화면에서 조건을 바꿔 calcRunId가 바뀌어도 다시 보내지 않는다.
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ApiError } from "../../../api/errors";
import type { ChatDeps } from "../index";
import type { ChatPollingState } from "../types";

type Store = ChatDeps["planStore"];
type PlanState = ReturnType<Store["getState"]>;

// 탭(모듈) 단위 기억. 같은 store를 보는 대화창이 여러 개여도, 닫았다 다시 열어도 이어진다. 새로고침(새 모듈)이면 사라진다.
interface TabMemory {
  /** store가 비워질 때마다 오른다. 요청 당시 세대와 다르면 늦은 응답을 무시한다. */
  generation: number;
  /** F-3을 이미 보낸 calcRunId(성공·실패 모두). */
  sent: Set<number>;
  /** 이 세대에서 자동 설명을 이미 보냈다(#280). 404 회복만 다시 보낼 수 있게 되돌린다. */
  explained: boolean;
  /**
   * 회복 재조회가 돌려준 결과(응답 객체)들. 이 결과 때문에 보낸 F-3이 또 404여도 더 불러오지 않는다(한 연쇄에 회복 1회).
   * id가 아니라 객체로 구분해서, 다른 경로로 먼저 들어와 이미 보낸 같은 id의 결과는 연쇄에 편입되지 않는다.
   */
  recovered: WeakSet<object>;
  /** 진행 중인 회복 재조회(실패해도 거부되지 않는다). */
  recoveries: Set<Promise<unknown>>;
  /** 지금 이 store에 붙어 있는 대화창들의 202 수신 함수. 요청을 보낸 대화창이 닫혀도 지금 열린 대화창이 받는다. */
  listeners: Set<() => void>;
}

const memories = new WeakMap<Store, TabMemory>();

function memoryOf(store: Store): TabMemory {
  const known = memories.get(store);
  if (known) return known;
  const memory: TabMemory = {
    generation: 0,
    sent: new Set(),
    explained: false,
    recovered: new WeakSet(),
    recoveries: new Set(),
    listeners: new Set(),
  };
  // 대화창이 닫혀 있는 동안의 비움(로그아웃·401)도 잊어야 해서 마운트와 무관하게 구독을 둔다.
  store.subscribe((next: PlanState, prev: PlanState) => {
    const emptied = next.revision === null && next.view === null;
    if (!emptied || (prev.revision === null && prev.view === null)) return;
    memory.generation += 1;
    memory.sent = new Set();
    memory.explained = false;
    memory.recovered = new WeakSet();
  });
  memories.set(store, memory);
  return memory;
}

/** 자동 설명을 보냈는지 적는다(#280). 훅 밖 함수로 둔다(훅 인자인 탭 메모리를 effect 안에서 직접 바꾸지 않는다). */
function markExplained(memory: TabMemory, explained: boolean): void {
  memory.explained = explained;
}

/** retryOfMessageId가 있으면 '설명 다시 요청'(FR-AI-05)이다. */
export function postExplanation(api: ChatDeps["api"], calcRunId: number, retryOfMessageId?: number): Promise<unknown> {
  const body = retryOfMessageId === undefined ? { calcRunId } : { calcRunId, retryOfMessageId };
  return api.request("POST", "/api/chat/explanations", body);
}

// 회복 재조회가 돌려준 결과는 store에 먼저 반영되고, 재조회 Promise는 그 뒤 정해진 몇 단계(async 반환, loadPlan의 finally, 아래 then·catch·finally)를 거쳐서야 끝난다.
// 그 사이에 그 결과의 F-3이 404로 먼저 돌아오면 같은 연쇄인지 알 수 없으니, 판정 전에 진행 중인 재조회를 잠깐 기다린다.
// 한도를 두는 이유: 끝없이 기다리면 아직 끝나지 않은 옛 재조회가 따로 들어온 새 결과의 첫 404 회복(AC-8 (8))을 막는다.
const SETTLE_TICKS = 20;

async function settleRecoveries(memory: TabMemory): Promise<void> {
  if (memory.recoveries.size === 0) return;
  const grace = (async () => {
    for (let tick = 0; tick < SETTLE_TICKS; tick += 1) await null;
  })();
  await Promise.race([Promise.all([...memory.recoveries]), grace]);
}

// 404: 결과를 다시 불러온다. 공유 store에서 하므로 대화창이 닫혀 있어도 한다. 다시 보내는 일은 바뀐 결과를 보는 effect가 한다.
// sentBody: 404를 받은 F-3을 보낼 때의 결과 객체. 이미 회복 재조회가 돌려준 결과면 그 연쇄의 회복은 끝났다.
async function recoverFromNotFound(store: Store, memory: TabMemory, sentBody: object, isCurrent: () => boolean): Promise<void> {
  await settleRecoveries(memory);
  if (!isCurrent() || memory.recovered.has(sentBody)) return;
  const { generation } = memory;
  const loading = store
    .loadPlan()
    .then((loaded) => {
      if (loaded && memory.generation === generation) memory.recovered.add(loaded);
    })
    .catch(() => undefined) // 재조회 실패는 아무것도 그리지 않는다
    .finally(() => memory.recoveries.delete(loading));
  memory.recoveries.add(loading);
}

type ChatPolling = Pick<ChatPollingState, "messages" | "hasPending" | "answerPending" | "pollingFailed"> & { resume(): void };

/**
 * polling: 대화창의 폴링 상태와 재개 함수. F-3 202를 받으면 새 설명을 받으러 폴링을 다시 돌린다.
 * 돌고 있는 폴링은 끊지 않고(떠 있는 F-2 응답을 버리지 않는다) 멈출 때 한 번 재개한다. 404 회복 같은 공유 store 일은 대화창과 무관하게 이어진다.
 */
export function useExplanationRequest(deps: ChatDeps, polling: ChatPolling): void {
  const { api, planStore: store } = deps;
  const memory = memoryOf(store);
  const subscribe = useCallback((onChange: () => void) => store.subscribe(() => onChange()), [store]);
  const view = useSyncExternalStore(subscribe, () => store.getState().view);
  // store가 비워질 때마다 오른다. clear()와 같은 결과의 복원이 한 번에 일어나 view가 그대로여도 effect가 다시 돈다.
  const generation = useSyncExternalStore(subscribe, () => memory.generation);
  const calcRunId = view?.calcRunId;
  const status = view?.status;

  const { messages, hasPending, answerPending, pollingFailed, resume } = polling;
  const [firstMessages] = useState(messages);
  const restored = messages !== firstMessages; // 마운트 직후 최초 복원 응답이 반영되면 바뀐다
  // 새로고침 뒤에도 서버 대화 기록에 결과 설명이 있으면 처음 진입이 아니다(#280). 그래서 기록을 받은 뒤에 정한다.
  // 기록을 못 받으면(연속 실패) 기다리지 않고 보낸다
  const historyReady = restored || pollingFailed;
  const historyHasExplanation = messages.some((message) => message.messageType === "EXPLANATION");

  useEffect(() => {
    if (status !== "CALCULATED" || typeof calcRunId !== "number" || !historyReady) return;
    if (historyHasExplanation) markExplained(memory, true);
    const current = store.getState().view;
    if (current?.calcRunId !== calcRunId || memory.sent.has(calcRunId) || memory.explained) return;
    memory.sent.add(calcRunId);
    markExplained(memory, true);
    const sentGeneration = memory.generation;
    const sentBody = current.body;
    // 지금 결과·세대의 응답만 쓴다. 늦게 온 옛 응답은 무시한다.
    const isCurrent = () => memory.generation === sentGeneration && store.getState().view?.calcRunId === calcRunId;
    postExplanation(api, calcRunId).then(
      () => {
        // 202 본문은 쓰지 않는다. 지금 붙어 있는 대화창이 폴링을 다시 돌려 서버가 만든 대기·완료 메시지를 받는다.
        if (isCurrent()) [...memory.listeners].forEach((listener) => listener());
      },
      (error: unknown) => {
        if (error instanceof ApiError && error.code === "NOT_FOUND" && isCurrent()) {
          // 결과가 바뀌어 없는 calcRunId였다. 다시 불러온 결과로는 아직 설명하지 않았으므로 한 번 더 보낼 수 있다
          markExplained(memory, false);
          void recoverFromNotFound(store, memory, sentBody, isCurrent);
        }
        // 400·500·네트워크는 그리지 않고, 401은 래퍼가 로그인으로 보낸다.
      },
    );
  }, [api, store, memory, status, calcRunId, generation, historyReady, historyHasExplanation]);

  // 질문 전송(F-1 202) 직후에는 응답이 오기 전이라 hasPending이 옛 값이어도 answerPending이 켜져 있다. 둘 중 하나면 폴링이 돌고 있다.
  const running = hasPending || answerPending;
  const wantedGeneration = useRef<number | null>(null);
  const [accepted, setAccepted] = useState(0);

  useEffect(() => {
    const onAccepted = () => {
      wantedGeneration.current = memory.generation;
      setAccepted((count) => count + 1);
    };
    memory.listeners.add(onAccepted);
    return () => {
      memory.listeners.delete(onAccepted);
    };
  }, [memory]);

  useEffect(() => {
    if (wantedGeneration.current === null) return;
    if (wantedGeneration.current !== memory.generation) {
      wantedGeneration.current = null; // 그 사이 store가 비워졌다(로그아웃·401)
      return;
    }
    // 멈춰 있을 때만 다시 돌린다: 대기가 없거나(최초 복원이 끝난 뒤) 연속 실패로 멈췄다. 돌고 있으면 떠 있는 응답을 반영한 뒤 멈출 때까지 기다린다.
    if (!pollingFailed && !(restored && !running)) return;
    wantedGeneration.current = null;
    resume();
  }, [memory, accepted, restored, running, pollingFailed, resume, generation]);
}
