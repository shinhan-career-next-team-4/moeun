// 화면이 Spring(또는 목)과 통신하는 유일한 통로. 화면 코드는 fetch를 직접 부르지 않는다.
// 같은 사이트 전제(SameSite=Lax 쿠키): 절대 URL 없이 상대 경로 /api/...만 쓰고 credentials: "include"로 쿠키를 싣는다.
import { LOGIN_PATH, SIGNUP_PATH } from "../app/routes";
import type { AuthChangeKind } from "./authSync";
import { ApiError, type ProblemBody } from "./errors";

export type ApiMode = "mock" | "live";
export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE" | "PATCH";

export function resolveApiMode(env: Record<string, unknown>): ApiMode {
  return env.VITE_API_MODE === "mock" ? "mock" : "live";
}

export interface ApiClientOptions {
  mode: ApiMode;
  /** 실서버 모드의 전송. 기본은 전역 fetch. */
  fetch?: typeof fetch;
  planStore: { clear(): void; loadPlan(options?: { fresh?: boolean }): Promise<unknown> };
  polling: { stopAll(): void };
  navigate(to: string): void;
  getPathname(): string;
  authSync?: { notifyAuthChanged(kind: AuthChangeKind): void };
}

export interface ApiClient {
  request<T = unknown>(method: HttpMethod, path: string, body?: unknown): Promise<T>;
}

const pathOf = (path: string) => path.split(/[?#]/)[0];
const mediaTypeOf = (response: Response) =>
  (response.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();

const noop = () => {};

export function createApiClient(options: ApiClientOptions): ApiClient {
  const { mode, planStore, polling, navigate, getPathname, authSync } = options;
  const liveFetch: typeof fetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));

  async function send(method: HttpMethod, path: string, body: unknown): Promise<Response> {
    if (mode === "mock") {
      // 목은 운영 번들에 들어가지 않도록 필요할 때 읽는다.
      const { mockFetch } = await import("./mock");
      return mockFetch(path, { method });
    }
    const init: RequestInit = { method, credentials: "include" };
    if (body !== undefined) {
      init.headers = { "Content-Type": "application/json" };
      init.body = JSON.stringify(body);
    }
    try {
      return await liveFetch(path, init);
    } catch (cause) {
      throw ApiError.network(cause);
    }
  }

  async function readJson(response: Response): Promise<unknown> {
    try {
      return await response.json();
    } catch (cause) {
      throw ApiError.network(cause);
    }
  }

  async function execute(method: HttpMethod, path: string, body: unknown): Promise<unknown> {
    const response = await send(method, path, body);
    const mediaType = mediaTypeOf(response);

    if (response.ok) {
      if (mediaType !== "application/json") throw ApiError.network();
      return readJson(response);
    }

    // problem+json만 code를 읽는다. 그 밖(502 HTML 등)은 code 없는 네트워크 오류다.
    if (mediaType !== "application/problem+json") throw ApiError.network();
    const problem = await readJson(response);
    if (typeof problem !== "object" || problem === null || Array.isArray(problem)) throw ApiError.network();
    const { errors } = problem as { errors?: unknown };
    const error = ApiError.fromProblem(response.status, {
      ...(problem as ProblemBody),
      errors: Array.isArray(errors) ? errors : [],
    });

    if (error.code === "UNAUTHENTICATED") {
      planStore.clear();
      polling.stopAll();
      // 로그인·회원가입 화면에서는 이동하지 않는다(입력 중인 양식을 지키려고, #460).
      const pathname = getPathname();
      if (pathname !== LOGIN_PATH && pathname !== SIGNUP_PATH) navigate(LOGIN_PATH);
    }
    throw error;
  }

  async function request<T = unknown>(method: HttpMethod, path: string, body?: unknown): Promise<T> {
    const target = pathOf(path);
    try {
      const result = await execute(method, path, body);
      // 회원가입(P-7) 성공도 로그인과 같은 쿠키를 받으므로 로그인으로 알린다(D-078, #460).
      if (method === "POST" && (target === "/api/auth/login" || target === "/api/auth/signup")) {
        authSync?.notifyAuthChanged("login");
      } else if (method === "POST" && target === "/api/auth/logout") {
        planStore.clear();
        authSync?.notifyAuthChanged("logout");
      }
      return result as T;
    } catch (error) {
      // 입력 저장(P-3) 응답이 유실되면 저장 여부를 모른다. 안내 뒤 서버 값으로 revision을 맞춘다(D-077).
      // 다른 요청에는 걸지 않는다(GET /api/plan은 무한 재귀가 된다).
      // 이미 떠난 GET은 저장 전 값을 가져올 수 있으므로 합류하지 않고 새로 조회한다.
      if (error instanceof ApiError && error.kind === "network" && method === "PUT" && target === "/api/plan") {
        new Promise((resolve) => resolve(planStore.loadPlan({ fresh: true }))).catch(noop);
      }
      throw error;
    }
  }

  return { request };
}
