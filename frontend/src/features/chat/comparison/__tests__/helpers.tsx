// Issue #53 수용 테스트 공용 도구. 잠긴 파일이라 구현 쪽에서 고치지 않는다.
// 공개 인터페이스(ChatWindow + spec.md "화면 계약"의 data-testid)만 쓴다. 내부 구조에는 의존하지 않는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { render } from "@testing-library/react";
import { expect, vi } from "vitest";
import { planView, readMock } from "../../../../api/__tests__/helpers";
import { createApiClient } from "../../../../api/client";
import { createPlanStore } from "../../../../api/planStore";
import { createPollingRegistry } from "../../../../api/polling";
import { ChatWindow } from "../../index";
import { flush } from "../../__tests__/helpers";

export { planView };

const here = path.dirname(fileURLToPath(import.meta.url));
export const COMPARISON_DIR = path.resolve(here, "..");

// ---------------------------------------------------------------- 화면 계약(spec.md)
export const APPLY_LABEL = "내 계획으로 적용";
export const COLLAPSED_LABEL = "이전 결과";
export const CONDITION_KEYS = ["monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths"];

export const card = (id: number) =>
  document.querySelector<HTMLElement>(`[data-testid="chat-comparison"][data-message-id="${id}"]`);
export const cards = () => Array.from(document.querySelectorAll<HTMLElement>('[data-testid="chat-comparison"]'));
export const applyButton = (id: number) =>
  card(id)?.querySelector<HTMLButtonElement>('[data-testid="chat-comparison-apply"]') ?? null;
export const applyButtons = () =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('[data-testid="chat-comparison-apply"]'));
export const conditionRows = (id: number) =>
  Array.from(card(id)?.querySelectorAll<HTMLElement>('[data-testid="chat-comparison-condition"]') ?? []);
export const scenarioRows = (id: number) =>
  Array.from(card(id)?.querySelectorAll<HTMLElement>('[data-testid="chat-comparison-scenario"]') ?? []);
/** 조건 행·시나리오 행 안의 칸 하나(spec.md 화면 계약의 칸 표식)의 글자. 칸이 없으면 null. */
export const cellText = (row: HTMLElement, testId: string) =>
  row.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.textContent?.trim() ?? null;
export const failedNotice = (id: number) =>
  document.querySelector<HTMLElement>(`[data-testid="chat-comparison-failed"][data-message-id="${id}"]`);
export const comparisonToast = () => document.querySelector<HTMLElement>('[data-testid="chat-comparison-toast"]');
/** 카드가 있어야 하는 곳에서 쓴다. 없으면 TypeError가 아니라 단언으로 실패한다. */
export function cardOf(id: number): HTMLElement {
  const el = card(id);
  expect(el, `메시지 ${id}의 비교 카드(chat-comparison)가 없다`).not.toBeNull();
  return el as HTMLElement;
}
/** 적용 버튼이 있어야 하는 곳에서 쓴다. 없으면 단언으로 실패한다. */
export function buttonOf(id: number): HTMLButtonElement {
  const el = applyButton(id);
  expect(el, `메시지 ${id}의 적용 버튼(chat-comparison-apply)이 없다`).not.toBeNull();
  return el as HTMLButtonElement;
}
export const bubble = (id: number) =>
  document.querySelector<HTMLElement>(`[data-testid="chat-message"][data-message-id="${id}"]`);

// ---------------------------------------------------------------- F-4 본문
/** 목 파일의 F-4 본문을 복사한다. messageId·applicable·changedKeys를 바꿀 수 있다. */
export function comparison(
  file = "get-comparison.200.json",
  patch: { messageId?: number; applicable?: boolean; changedKeys?: string[] } = {},
) {
  const body = structuredClone(readMock(file).responses[0].body);
  return { ...body, ...patch };
}

// ---------------------------------------------------------------- 가짜 api(경로별 응답)
export type Reply = unknown | Error | Promise<unknown> | (() => unknown);
export type Call = { method: string; path: string; body?: unknown };

const F4 = /^\/api\/chat\/messages\/([^/]+)\/comparison$/;
const F5 = /^\/api\/chat\/messages\/([^/]+)\/apply$/;

/** 요청을 키로 나눈다: "F2"(GET 메시지 목록·폴링), "F4 <id>", "F5 <id>", 그 밖은 "METHOD path". */
export function routeKey(method: string, requestPath: string): string {
  const pathOnly = requestPath.split(/[?#]/)[0];
  const f4 = F4.exec(pathOnly);
  if (method === "GET" && f4) return `F4 ${f4[1]}`;
  const f5 = F5.exec(pathOnly);
  if (method === "POST" && f5) return `F5 ${f5[1]}`;
  if (method === "GET" && pathOnly === "/api/chat/messages") return "F2";
  return `${method} ${pathOnly}`;
}

/**
 * 키마다 응답 큐를 소비하는 api.request. 큐 끝을 넘으면 마지막 응답을 반복한다.
 * Error는 던지고 Promise는 기다린다. 스크립트에 없는 키는 Error를 던진다.
 */
export function makeApi(routes: Record<string, Reply[]>) {
  const queues = new Map(Object.entries(routes).map(([key, replies]) => [key, [...replies]]));
  const cursor = new Map<string, number>();
  const calls: Call[] = [];
  const request = vi.fn(async (method: string, requestPath: string, body?: unknown) => {
    calls.push({ method, path: requestPath, body });
    const key = routeKey(method, requestPath);
    const queue = queues.get(key);
    if (!queue || queue.length === 0) throw new Error(`스크립트에 없는 호출: ${method} ${requestPath}`);
    const index = cursor.get(key) ?? 0;
    cursor.set(key, index + 1);
    const entry = queue[Math.min(index, queue.length - 1)];
    const value = await (typeof entry === "function" ? (entry as () => unknown)() : entry);
    if (value instanceof Error) throw value;
    return value;
  });
  const f4Calls = () => calls.filter((c) => routeKey(c.method, c.path).startsWith("F4 ")).map((c) => c.path);
  const f5Calls = () => calls.filter((c) => routeKey(c.method, c.path).startsWith("F5 "));
  return { request, calls, f4Calls, f5Calls };
}

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// ---------------------------------------------------------------- 공유 계획 store(#13)
/**
 * 진짜 공유 store. GET /api/plan은 planReplies를 차례로 돌려준다(끝을 넘으면 마지막 반복).
 * planGets에는 GET을 보낼 때의 store revision을 남긴다. initialRevision이 있으면 최초 로드(null -> N)를 한다.
 */
export function makeStore({
  initialRevision = 12 as number | null,
  planReplies = [planView(13)] as Reply[],
} = {}) {
  const navigations: string[] = [];
  const planGets: Array<{ revisionAtSend: number | null }> = [];
  let index = 0;
  const holder: { store?: ReturnType<typeof createPlanStore> } = {};
  const store = createPlanStore({
    api: {
      request: async (method: "GET", requestPath: string) => {
        planGets.push({ revisionAtSend: holder.store!.getState().revision });
        if (method !== "GET" || requestPath !== "/api/plan") throw new Error(`store가 부른 예상 밖 호출: ${requestPath}`);
        const entry = planReplies[Math.min(index, planReplies.length - 1)];
        index += 1;
        const value = await (typeof entry === "function" ? (entry as () => unknown)() : entry);
        if (value instanceof Error) throw value;
        return value;
      },
    },
    navigate: (to: string) => {
      navigations.push(to);
    },
  });
  holder.store = store;
  if (initialRevision !== null) store.acceptPlanView(planView(initialRevision));
  return { store, navigations, planGets };
}

// ---------------------------------------------------------------- 마운트
export async function mountChat(api: { request: unknown }, store: unknown) {
  const polling = createPollingRegistry();
  const deps = { api, polling, planStore: store } as any;
  const view = render(<ChatWindow deps={deps} />);
  await flush();
  await flush();
  return { deps, polling, view };
}

// ---------------------------------------------------------------- 실서버 모드 래퍼(401 확인용)
/** 진짜 래퍼(live)에 가짜 fetch를 꽂는다. 401이면 래퍼가 /login으로 보낸다. */
export function makeLiveEnv(respond: (method: string, requestPath: string, body: unknown) => Response) {
  const navigations: string[] = [];
  const polling = createPollingRegistry();
  const holder: { client?: ReturnType<typeof createApiClient> } = {};
  const store = createPlanStore({
    api: { request: (method: "GET", p: string) => holder.client!.request(method, p) },
    navigate: (to) => navigations.push(to),
  });
  const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.pathname + input.search : input.url;
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    return respond(init?.method ?? "GET", url, body);
  });
  holder.client = createApiClient({
    mode: "live",
    fetch: fetchStub as unknown as typeof fetch,
    planStore: { clear: () => store.clear(), loadPlan: () => store.loadPlan() },
    polling,
    navigate: (to) => navigations.push(to),
    getPathname: () => "/result",
  });
  store.acceptPlanView(planView(12));
  return { api: holder.client, store, navigations, polling, fetchStub };
}

/** 비교 폴더의 구현 소스(테스트 제외) 경로와 내용. */
export function comparisonSources(): Array<{ file: string; text: string }> {
  if (!fs.existsSync(COMPARISON_DIR)) return [];
  const out: Array<{ file: string; text: string }> = [];
  const walk = (dir: string) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== "__tests__") walk(full);
      } else if (/\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name)) {
        out.push({ file: path.relative(COMPARISON_DIR, full), text: fs.readFileSync(full, "utf8") });
      }
    }
  };
  walk(COMPARISON_DIR);
  return out;
}
