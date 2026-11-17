// 공유 계획 store (C12a-6-5, C12a-6-7, D-069, D-072). 계획 상태의 읽기·쓰기는 이 store 하나만 한다.
// 새 상태 관리 라이브러리 없이 구독 API(useSyncExternalStore 호환)만 둔다.
import { INPUT_PATH } from "../../app/routes";
import { ApiError } from "../errors";

export type PlanStatus = "CALCULATED" | "ALREADY_EXCEEDED";

/** GET/PUT /api/plan 응답. 프론트가 읽는 칸만 적고 나머지는 그대로 들고 다닌다. */
export interface PlanView {
  revision: number;
  calcRunId: number;
  result: { status: PlanStatus; [key: string]: unknown };
  [key: string]: unknown;
}

export interface PlanViewSlot {
  revision: number;
  calcRunId: number;
  status: PlanStatus;
  body: PlanView;
}

export interface PlanState {
  revision: number | null;
  view: PlanViewSlot | null;
  /** view.revision === revision이면 view.status, 아니면 직전 값 유지. 둘 다 null이면 null. */
  settledStatus: PlanStatus | null;
}

export interface PlanStore {
  getState(): PlanState;
  subscribe(listener: (state: PlanState, prev: PlanState) => void): () => void;
  /** revision이 null에서 N으로 처음 채워질 때는 부르지 않는다. N→M(M>N), N→null에는 부른다. */
  onRevisionChange(listener: (next: number | null, prev: number | null) => void): () => void;
  acceptRevision(n: number): void;
  acceptPlanView(view: PlanView): void;
  /** 요청 시점 revision이 null이었는데 지금 값이 있으면 무시(false). 그 밖에는 비우고 입력 화면으로(true). */
  acceptNoPlan(sentRevision: number | null): boolean;
  clear(): void;
  /** fresh: true면 진행 중 요청에 합류하지 않고 새로 보낸다(저장 이후의 서버 값이 필요할 때). */
  loadPlan(options?: LoadPlanOptions): Promise<PlanView | null>;
}

export interface LoadPlanOptions {
  fresh?: boolean;
}

export function createPlanStore({
  api,
  navigate,
}: {
  api: { request(method: "GET", path: string): Promise<unknown> };
  navigate(to: string): void;
}): PlanStore {
  let state: PlanState = { revision: null, view: null, settledStatus: null };
  const listeners = new Set<(state: PlanState, prev: PlanState) => void>();
  const revisionListeners = new Set<(next: number | null, prev: number | null) => void>();

  function update(revision: number | null, view: PlanViewSlot | null, settledStatus: PlanStatus | null): void {
    if (revision === state.revision && view === state.view && settledStatus === state.settledStatus) return;
    const prev = state;
    state = { revision, view, settledStatus };
    [...listeners].forEach((listener) => listener(state, prev));
    if (prev.revision !== revision && prev.revision !== null) {
      [...revisionListeners].forEach((listener) => listener(revision, prev.revision));
    }
  }

  // 두 칸을 바꾼 뒤 파생값을 다시 구한다. view가 revision을 따라잡지 못한 동안에는 직전 값을 유지한다.
  function set(revision: number | null, view: PlanViewSlot | null): void {
    const settled = view !== null && view.revision === revision ? view.status : state.settledStatus;
    update(revision, view, settled);
  }

  const nextRevision = (n: number) => (state.revision === null || n > state.revision ? n : state.revision);

  function acceptRevision(n: number): void {
    set(nextRevision(n), state.view);
  }

  function acceptPlanView(view: PlanView): void {
    const slot: PlanViewSlot | null =
      state.view === null || view.revision >= state.view.revision
        ? { revision: view.revision, calcRunId: view.calcRunId, status: view.result.status, body: view }
        : state.view;
    set(nextRevision(view.revision), slot);
  }

  // 인증이 바뀌거나 비워질 때마다 올린다. 이전 세대에서 시작한 GET은 합류 대상도, 반영 대상도 아니다.
  let generation = 0;
  let inflight: { sentRevision: number | null; promise: Promise<PlanView | null> } | null = null;

  function clear(): void {
    generation += 1;
    inflight = null;
    update(null, null, null);
  }

  function acceptNoPlan(sentRevision: number | null): boolean {
    if (sentRevision === null && state.revision !== null) return false;
    clear();
    navigate(INPUT_PATH);
    return true;
  }

  async function fetchPlan(sentRevision: number | null): Promise<PlanView | null> {
    const sentGeneration = generation;
    try {
      const view = (await api.request("GET", "/api/plan")) as PlanView;
      // 요청 중에 비워졌으면(로그아웃·계정 전환·401) 이전 인증의 응답이므로 버리고 새로 조회한다.
      if (sentGeneration !== generation) return loadPlan();
      acceptPlanView(view);
      return view;
    } catch (error) {
      if (error instanceof ApiError && error.code === "NO_PLAN") {
        if (sentGeneration !== generation) return loadPlan();
        acceptNoPlan(sentRevision);
        return null;
      }
      throw error;
    }
  }

  // single-flight: 진행 중 요청이 지금과 같은 세대·같은 revision에서 시작됐을 때만 합류한다.
  // fresh면 합류하지 않는다: 이미 떠난 GET은 저장(PUT)보다 앞선 서버 값을 가져올 수 있다.
  function loadPlan({ fresh = false }: LoadPlanOptions = {}): Promise<PlanView | null> {
    const sentRevision = state.revision;
    if (!fresh && inflight && inflight.sentRevision === sentRevision) return inflight.promise;
    const entry = {
      sentRevision,
      promise: fetchPlan(sentRevision).finally(() => {
        if (inflight === entry) inflight = null;
      }),
    };
    inflight = entry;
    return entry.promise;
  }

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    onRevisionChange(listener) {
      revisionListeners.add(listener);
      return () => {
        revisionListeners.delete(listener);
      };
    },
    acceptRevision,
    acceptPlanView,
    acceptNoPlan,
    clear,
    loadPlan,
  };
}
