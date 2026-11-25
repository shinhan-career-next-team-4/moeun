// Issue #52 수용 테스트 공용 도구. 잠긴 파일이라 구현 쪽에서 고치지 않는다.
// 공개 인터페이스(spec.md "공개 인터페이스")만 쓴다. 내부 구조에는 의존하지 않는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { act } from "@testing-library/react";
import { vi } from "vitest";
import { createApiClient } from "../../../api/client";
import { ApiError } from "../../../api/errors";
import { createPlanStore } from "../../../api/planStore";
import { createPollingRegistry } from "../../../api/polling";

const here = path.dirname(fileURLToPath(import.meta.url));
export const FEATURE_DIR = path.resolve(here, "..");
const COPY_FILE = path.resolve(here, "../../../../../copy/copy.ko.yml");

// ---------------------------------------------------------------- 문구표
// copy/copy.ko.yml을 읽는다(들여쓰기 2칸, 따옴표 친 한 줄 값). 키는 '.'으로 이은 평탄 키다.
export function loadCopy(): Record<string, string> {
  const result: Record<string, string> = {};
  const stack: Array<{ indent: number; key: string }> = [];
  for (const raw of fs.readFileSync(COPY_FILE, "utf8").split("\n")) {
    if (/^\s*(#|$)/.test(raw)) continue;
    const indent = raw.length - raw.trimStart().length;
    const match = raw.trim().match(/^("?)([^":]+)\1:\s*(?:"(.*)")?\s*$/);
    if (!match) continue;
    const [, quoted, name, value] = match;
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    if (value === undefined) {
      stack.push({ indent, key: name });
    } else {
      // 따옴표 친 평탄 키("outOfScope.tax")는 그대로 쓴다.
      const key = quoted ? name : [...stack.map((s) => s.key), name].join(".");
      result[key] = value;
    }
  }
  return result;
}

export const copy = loadCopy();

// ---------------------------------------------------------------- 메시지 만들기
export type TestMessage = {
  id: number;
  role: "USER" | "ASSISTANT";
  messageType: string | null;
  status: "PENDING" | "COMPLETED" | "FAILED";
  content: string | null;
  citations: Array<{ title: string; sourceUrl: string; orgName: string }>;
  createdAt: string;
  [key: string]: unknown;
};

const createdAt = "2026-11-16T10:15:30+09:00";

export const userMsg = (id: number, content = "월 50만원으로 하면 어때요?"): TestMessage => ({
  id,
  role: "USER",
  messageType: "QUESTION",
  status: "COMPLETED",
  content,
  citations: [],
  createdAt,
});

export const pendingMsg = (id: number): TestMessage => ({
  id,
  role: "ASSISTANT",
  messageType: null,
  status: "PENDING",
  content: null,
  citations: [],
  createdAt,
});

export const doneMsg = (id: number, messageType: string, content: string): TestMessage => ({
  id,
  role: "ASSISTANT",
  messageType,
  status: "COMPLETED",
  content,
  citations: [],
  createdAt,
});

export const failedMsg = (id: number): TestMessage => ({
  id,
  role: "ASSISTANT",
  messageType: "FAILURE",
  status: "FAILED",
  content: copy["chat.failed"],
  citations: [],
  createdAt,
});

export const page = (messages: TestMessage[], hasPending: boolean, answerPending = hasPending) => ({
  messages,
  hasPending,
  answerPending,
});

// ---------------------------------------------------------------- 오류
export const problem = (status: number, code: string, errors: unknown[] = []) =>
  ApiError.fromProblem(status, { status, code, errors });
export const unauthenticated = () => problem(401, "UNAUTHENTICATED");
export const serverError = () => problem(500, "INTERNAL_ERROR");
export const malformed = () => problem(400, "MALFORMED_REQUEST");
export const networkError = () => ApiError.network();

// ---------------------------------------------------------------- 가짜 api
export type Reply = unknown | Error | Promise<unknown> | (() => unknown);
export type Call = { method: string; path: string; body?: unknown };

/** F-2(메시지 목록 복원·폴링) 경로인가. `/api/chat/messages`와 `?afterId=`만 해당하고 F-4(`/{id}/comparison`)는 아니다. */
export const isF2Path = (requestPath: string) => requestPath.split(/[?#]/)[0] === "/api/chat/messages";
/** F-4(`/api/chat/messages/{id}/comparison`) 경로인가. */
const isF4Path = (requestPath: string) => /^\/api\/chat\/messages\/\d+\/comparison$/.test(requestPath.split(/[?#]/)[0]);

/**
 * 호출 순서대로 응답을 소비하는 api.request. 배열 끝을 넘으면 마지막 응답을 반복한다(목과 같다).
 * Error 인스턴스는 던지고, Promise는 기다린다.
 * F-4(#53 비교 조회) GET은 큐를 소비하지 않고 404 NOT_FOUND로 답한다(#53 사용자 결정 4차 B1=A). 그 밖의 GET(예: #54의 공유 store가 쓰는 GET /api/plan)은 큐를 그대로 쓴다.
 */
export function makeFakeApi(replies: { get?: Reply[]; post?: Reply[] }) {
  const queues = { GET: [...(replies.get ?? [])], POST: [...(replies.post ?? [])] };
  const cursor = { GET: 0, POST: 0 };
  const calls: Call[] = [];
  const request = vi.fn(async (method: string, requestPath: string, body?: unknown) => {
    calls.push({ method, path: requestPath, body });
    if (method === "GET" && isF4Path(requestPath)) throw problem(404, "NOT_FOUND");
    const key = method as "GET" | "POST";
    const queue = queues[key];
    if (!queue || queue.length === 0) throw new Error(`스크립트에 없는 호출: ${method} ${requestPath}`);
    const entry = queue[Math.min(cursor[key], queue.length - 1)];
    cursor[key] += 1;
    const value = await (typeof entry === "function" ? (entry as () => unknown)() : entry);
    if (value instanceof Error) throw value;
    return value;
  });
  const gets = () => calls.filter((c) => c.method === "GET" && isF2Path(c.path));
  const posts = () => calls.filter((c) => c.method === "POST");
  return { request, calls, gets, posts };
}

/** 폴링 레지스트리와 함께 묶은 의존. */
export function makeDeps(api: { request: unknown }, planStore?: any) {
  const polling = createPollingRegistry();
  return {
    deps: { api: api as any, polling, planStore: planStore ?? makePlanStore() },
    polling,
  };
}

// ---------------------------------------------------------------- 계획 store
/** 요청을 보내지 않는 진짜 공유 store(#13). */
export function makePlanStore(navigate: (to: string) => void = () => {}) {
  return createPlanStore({ api: { request: async () => ({}) }, navigate });
}

/** getState·subscribe만 가진 읽기 전용 store. 다른 API(onRevisionChange 등)를 부르면 TypeError로 드러난다. */
export function makeReadOnlyStore(initial: { revision: number | null; settledStatus: "CALCULATED" | "ALREADY_EXCEEDED" | null }) {
  let state: any = { revision: initial.revision, view: null, settledStatus: initial.settledStatus };
  const listeners = new Set<(next: any, prev: any) => void>();
  return {
    getState: () => state,
    subscribe(listener: (next: any, prev: any) => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    set(patch: Record<string, unknown>) {
      const prev = state;
      state = { ...state, ...patch };
      [...listeners].forEach((l) => l(state, prev));
    },
  };
}

// ---------------------------------------------------------------- 목 모드 환경
/** 래퍼(#13)를 mock 모드로 만들고 폴링 레지스트리·store·navigate를 묶는다. 요청 기록도 남긴다. */
export function makeMockEnv(pathname = "/result") {
  const navigations: string[] = [];
  let currentPath = pathname;
  const navigate = (to: string) => {
    navigations.push(to);
    currentPath = to;
  };
  const polling = createPollingRegistry();
  const holder: { client?: ReturnType<typeof createApiClient> } = {};
  const requests: Array<{ method: string; path: string; body?: unknown }> = [];
  const store = createPlanStore({
    api: { request: (method: "GET", p: string) => holder.client!.request(method, p) },
    navigate,
  });
  holder.client = createApiClient({
    mode: "mock",
    planStore: { clear: () => store.clear(), loadPlan: () => store.loadPlan() },
    polling,
    navigate,
    getPathname: () => currentPath,
  });
  const api = {
    request: (method: any, p: string, body?: unknown) => {
      requests.push({ method, path: p, body });
      return holder.client!.request(method, p, body);
    },
  };
  return { api, store, polling, navigations, requests, deps: { api, polling, planStore: store } };
}

// ---------------------------------------------------------------- 시간
export async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

export const flush = () => tick(0);

export const restoreQuery = (c: Call) => c.path === "/api/chat/messages";
export const pollPath = (afterId: number) => `/api/chat/messages?afterId=${afterId}`;
