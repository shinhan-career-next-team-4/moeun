// 앱이 쓰는 기본 인스턴스를 조립한다. store가 api.request를 쓰고 래퍼가 store를 쓰는 순환은 지연 참조로 푼다.
import { createAuthSync } from "./authSync";
import { createApiClient, resolveApiMode } from "./client";
import { applyMockFilesFromEnv } from "./mock";
import { polling } from "./polling";
import { createPlanStore } from "./planStore";

type Navigate = (to: string) => void;

let routerNavigate: Navigate | null = null;

/** 라우터가 준비되면(NavigateBridge) 연결한다. 그 전에는 주소를 직접 바꾼다. */
export function setNavigate(navigate: Navigate | null): void {
  routerNavigate = navigate;
}

const navigate: Navigate = (to) => {
  if (routerNavigate) routerNavigate(to);
  else window.location.assign(to);
};

const lazy: { api?: ReturnType<typeof createApiClient> } = {};

export const planStore = createPlanStore({
  api: { request: (method, path) => lazy.api!.request(method, path) },
  navigate,
});

export const authSync = createAuthSync({ store: planStore });

export const api = createApiClient({
  mode: resolveApiMode(import.meta.env),
  planStore,
  polling,
  navigate,
  getPathname: () => window.location.pathname,
  authSync,
});
lazy.api = api;

// 목 모드에서만 쓴다. VITE_MOCK_FILES로 기본 목 파일을 골라 바꾼다(개발용)
if (resolveApiMode(import.meta.env) === "mock") applyMockFilesFromEnv(import.meta.env);

export { polling };
