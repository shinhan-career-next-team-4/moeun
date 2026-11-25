// Issue #13 수용 테스트 공용 도구. 잠긴 파일이라 구현 쪽에서 고치지 않는다.
// 공개 인터페이스(spec.md "공개 인터페이스")만 쓴다. 내부 구조에는 의존하지 않는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { vi } from "vitest";
import { createApiClient } from "../client";
import { createPlanStore } from "../planStore";

export const MOCKS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../mocks");
const MOCK_FOLDERS = ["chat", "auth", "plan", "alternative"] as const;

export type MockResponse = {
  status: number;
  headers: Record<string, string>;
  body: any;
};

export function allMockFiles(): string[] {
  return MOCK_FOLDERS.flatMap((folder) =>
    fs.readdirSync(path.join(MOCKS_DIR, folder)).filter((name) => name.endsWith(".json")),
  );
}

export function readMock(file: string): { responses: MockResponse[] } {
  for (const folder of MOCK_FOLDERS) {
    const p = path.join(MOCKS_DIR, folder, file);
    if (fs.existsSync(p)) return JSON.parse(fs.readFileSync(p, "utf8"));
  }
  throw new Error(`목 파일이 없다: ${file}`);
}

/** 목 응답 하나를 실제 fetch 응답 모양(Response)으로. */
export function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

export function problemResponse(status: number, code: string, errors: unknown[] = []): Response {
  return jsonResponse(
    status,
    { type: "about:blank", title: "t", status, code, errors },
    "application/problem+json",
  );
}

export function htmlResponse(status: number): Response {
  return new Response("<html><body>Bad Gateway</body></html>", {
    status,
    headers: { "Content-Type": "text/html" },
  });
}

/** `get-plan.200.json`(rev 12, CALCULATED) 본문을 복사해 revision·status만 바꾼 PlanView. */
export function planView(revision: number, status: "CALCULATED" | "ALREADY_EXCEEDED" = "CALCULATED") {
  const base = structuredClone(readMock("get-plan.200.json").responses[0].body);
  base.revision = revision;
  base.calcRunId = 1000 + revision;
  base.result.status = status;
  return base;
}

export function deferred<T = Response>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

type HarnessOptions = {
  mode: "mock" | "live";
  fetch?: typeof fetch;
  pathname?: string;
  authSync?: { notifyAuthChanged(kind: "login" | "logout"): void };
};

/**
 * 래퍼 + 공유 store를 spec.md의 조립 방식대로 묶는다.
 * calls에는 부수효과가 일어난 순서가 쌓인다: "clear", "stopAll", "navigate:<경로>".
 */
export function makeHarness(options: HarnessOptions) {
  const calls: string[] = [];
  let pathname = options.pathname ?? "/";
  const navigate = vi.fn((to: string) => {
    calls.push(`navigate:${to}`);
    pathname = to;
  });
  const polling = {
    stopAll: vi.fn(() => {
      calls.push("stopAll");
    }),
  };

  const holder: { client?: ReturnType<typeof createApiClient> } = {};
  const store = createPlanStore({
    api: { request: (method: "GET", p: string) => holder.client!.request(method, p) },
    navigate,
  });
  const loadPlanSpy = vi.fn(() => store.loadPlan());
  holder.client = createApiClient({
    mode: options.mode,
    fetch: options.fetch,
    planStore: {
      clear: () => {
        calls.push("clear");
        store.clear();
      },
      loadPlan: loadPlanSpy,
    },
    polling,
    navigate,
    getPathname: () => pathname,
    authSync: options.authSync,
  });

  return {
    client: holder.client,
    store,
    navigate,
    polling,
    calls,
    loadPlanSpy,
    setPathname(p: string) {
      pathname = p;
    },
  };
}
