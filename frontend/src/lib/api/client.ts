import type { FieldErrorDto, PlanView } from "./types";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
export const EXPIRED_LOGIN_PATH = "/login?expired=1";

export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;
  readonly errors: FieldErrorDto[];
  readonly plan?: PlanView;

  constructor(status: number, body: ProblemBody = {}) {
    super(body.detail ?? `API ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.detail = body.detail;
    this.errors = body.errors ?? [];
    this.plan = body.plan ?? undefined;
  }
}

interface ProblemBody {
  detail?: string;
  errors?: FieldErrorDto[];
  plan?: PlanView | null;
}

interface ApiFetchInit {
  method?: "GET" | "POST";
  body?: unknown;
  /** 로그인·로그인 확인처럼 401을 호출한 쪽이 직접 처리하는 요청 */
  skipAuthRedirect?: boolean;
}

/** 백엔드 API 호출. 쿠키 인증이라 credentials: include. 401이면 만료 안내와 함께 로그인 화면으로 보낸다. */
export async function apiFetch<T>(path: string, init: ApiFetchInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: init.method ?? "GET",
      credentials: "include",
      headers: init.body === undefined ? {} : { "Content-Type": "application/json" },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch {
    throw new ApiError(0, { detail: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." });
  }

  if (response.status === 401 && !init.skipAuthRedirect) {
    // 컴포넌트 밖 공통 함수라 라우터가 없다. 만료 시 화면 상태를 모두 버리려고 전체 이동한다.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign(EXPIRED_LOGIN_PATH);
  }
  if (!response.ok) {
    throw new ApiError(response.status, await readJson<ProblemBody>(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await readJson<T>(response)) as T;
}

async function readJson<T>(response: Response): Promise<T | undefined> {
  const text = await response.text();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return undefined;
  }
}
