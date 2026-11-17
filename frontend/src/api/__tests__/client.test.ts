// AC-3, AC-6, AC-8(credentials), AC-9, AC-10, AC-11(래퍼 경유), AC-19, AC-20(보내는 쪽): fetch 래퍼.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../errors";
import { resolveApiMode } from "../client";
import { resetMocks, useMock } from "../mock";
import { LOGIN_PATH } from "../../app/routes";
import { htmlResponse, jsonResponse, makeHarness, planView, problemResponse, readMock } from "./helpers";

beforeEach(() => resetMocks());

const liveWith = (impl: (url: string, init?: RequestInit) => Promise<Response> | Response, pathname?: string) => {
  const fetchSpy = vi.fn(async (url: string, init?: RequestInit) => impl(url, init));
  return { fetchSpy, ...makeHarness({ mode: "live", fetch: fetchSpy as unknown as typeof fetch, pathname }) };
};

async function caught(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error("거절되어야 하는데 성공했다");
}

describe("AC-8 실서버 모드: 같은 사이트로 나가고 쿠키를 싣는다", () => {
  it("상대 경로 /api/... 로 요청하고 credentials: 'include' 가 붙는다", async () => {
    const h = liveWith(() => jsonResponse(200, { ok: true }));
    await h.client.request("GET", "/api/plan");
    const [url, init] = h.fetchSpy.mock.calls[0];
    expect(url).toBe("/api/plan");
    expect(init?.credentials).toBe("include");
    expect(init?.method).toBe("GET");
  });

  it("본문이 있으면 JSON 문자열로 보내고 Content-Type: application/json 을 붙인다", async () => {
    const h = liveWith(() => jsonResponse(200, planView(12)));
    await h.client.request("PUT", "/api/plan", { expectedRevision: null, heldAssets: 5000000 });
    const [url, init] = h.fetchSpy.mock.calls[0];
    expect(url).toBe("/api/plan");
    expect(init?.method).toBe("PUT");
    expect(init?.credentials).toBe("include");
    expect(new Headers(init?.headers).get("content-type")).toBe("application/json");
    expect(JSON.parse(init?.body as string)).toEqual({ expectedRevision: null, heldAssets: 5000000 });
  });

  it("성공(application/json)이면 파싱한 본문을 돌려준다", async () => {
    const h = liveWith(() => jsonResponse(200, { revision: 13 }));
    await expect(h.client.request("POST", "/api/chat/messages/1031/apply", { expectedRevision: 12 })).resolves.toEqual({
      revision: 13,
    });
  });

  it("쿼리가 붙은 경로도 그대로 나간다", async () => {
    const h = liveWith(() => jsonResponse(200, {}));
    await h.client.request("GET", "/api/chat/messages?afterId=5");
    expect(h.fetchSpy.mock.calls[0][0]).toBe("/api/chat/messages?afterId=5");
  });
});

describe("AC-8 모드 선택", () => {
  it("VITE_API_MODE=mock 일 때만 mock, 그 밖에는 live", () => {
    expect(resolveApiMode({ VITE_API_MODE: "mock" })).toBe("mock");
    expect(resolveApiMode({ VITE_API_MODE: "live" })).toBe("live");
    expect(resolveApiMode({})).toBe("live");
  });
});

describe("AC-3 UNAUTHENTICATED: 상태를 비우고 폴링을 멈춘 뒤 로그인 경로로 이동한다 (분기는 code)", () => {
  it("401 UNAUTHENTICATED -> clear, stopAll, navigate(LOGIN_PATH) 순서로 한 번씩, 호출자에게는 ApiError", async () => {
    const h = liveWith(() => problemResponse(401, "UNAUTHENTICATED"));
    const err = await caught(h.client.request("GET", "/api/plan"));
    expect(err.code).toBe("UNAUTHENTICATED");
    expect(h.calls).toEqual(["clear", "stopAll", `navigate:${LOGIN_PATH}`]);
  });

  it("공유 store의 두 칸이 실제로 null이 된다", async () => {
    const h = liveWith(() => problemResponse(401, "UNAUTHENTICATED"));
    h.store.acceptPlanView(planView(12));
    expect(h.store.getState().revision).toBe(12);
    await caught(h.client.request("GET", "/api/chat/messages"));
    expect(h.store.getState()).toMatchObject({ revision: null, view: null, settledStatus: null });
  });

  it("HTTP 상태가 아니라 code로 분기한다: 상태 500이어도 code가 UNAUTHENTICATED면 같은 처리", async () => {
    const h = liveWith(() => problemResponse(500, "UNAUTHENTICATED"));
    await caught(h.client.request("GET", "/api/plan"));
    expect(h.calls).toEqual(["clear", "stopAll", `navigate:${LOGIN_PATH}`]);
  });

  it("목 모드에서도 같다: get-messages.401.json(폴링 중 만료)", async () => {
    const h = makeHarness({ mode: "mock" });
    useMock("get-messages.401.json");
    const err = await caught(h.client.request("GET", "/api/chat/messages?afterId=1"));
    expect(err.status).toBe(401);
    expect(err.code).toBe("UNAUTHENTICATED");
    expect(h.calls).toEqual(["clear", "stopAll", `navigate:${LOGIN_PATH}`]);
  });
});

describe("AC-9 로그인 화면에 머무는 경우", () => {
  it("P-1 401 INVALID_CREDENTIALS는 이동·비우기·폴링 중단 없이 ApiError만 던진다", async () => {
    const h = liveWith(() => problemResponse(401, "INVALID_CREDENTIALS"), LOGIN_PATH);
    const err = await caught(h.client.request("POST", "/api/auth/login", { loginId: "a", password: "b" }));
    expect(err.code).toBe("INVALID_CREDENTIALS");
    expect(err.status).toBe(401);
    expect(h.calls).toEqual([]);
  });

  it("목 모드 post-login.401-invalid.json도 같다", async () => {
    const h = makeHarness({ mode: "mock", pathname: LOGIN_PATH });
    useMock("post-login.401-invalid.json");
    const err = await caught(h.client.request("POST", "/api/auth/login", {}));
    expect(err.code).toBe("INVALID_CREDENTIALS");
    expect(h.calls).toEqual([]);
  });

  it("이미 로그인 화면에 있을 때 UNAUTHENTICATED는 상태 비우기·폴링 중단만 하고 다시 이동하지 않는다", async () => {
    const h = liveWith(() => problemResponse(401, "UNAUTHENTICATED"), LOGIN_PATH);
    await caught(h.client.request("GET", "/api/plan"));
    expect(h.calls).toEqual(["clear", "stopAll"]);
    expect(h.navigate).not.toHaveBeenCalled();
  });
});

describe("AC-10 problem+json이 아니거나 JSON 파싱에 실패하면 code 없음 -> 네트워크 오류 경로", () => {
  const networkCases: Array<[string, () => Promise<Response> | Response]> = [
    ["502 text/html (프록시 오류 페이지)", () => htmlResponse(502)],
    [
      "problem+json 헤더인데 본문이 JSON이 아님",
      () => new Response("not json", { status: 500, headers: { "Content-Type": "application/problem+json" } }),
    ],
    ["200인데 본문이 HTML", () => htmlResponse(200)],
    [
      "application/json 오류 본문(problem+json이 아님)은 code가 있어도 무시",
      () => jsonResponse(401, { code: "UNAUTHENTICATED", errors: [] }, "application/json"),
    ],
    ["fetch 자체가 거절(연결 끊김)", () => Promise.reject(new TypeError("Failed to fetch"))],
  ];

  it.each(networkCases)("%s", async (_name, impl) => {
    const h = liveWith(impl);
    const err = await caught(h.client.request("GET", "/api/chat/messages"));
    expect(err.kind).toBe("network");
    expect(err.code).toBeNull();
    expect(err.messageKey).toBe("common.networkError");
    expect(h.calls).toEqual([]); // 로그인 이동·비우기 없음
  });

  it("Content-Type에 charset이 붙어도 problem+json으로 읽는다", async () => {
    const h = liveWith(() =>
      jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json;charset=UTF-8"),
    );
    const err = await caught(h.client.request("GET", "/api/plan"));
    expect(err.kind).toBe("problem");
    expect(err.code).toBe("NO_PLAN");
  });

  it("problem+json 오류는 kind 'problem', status·code·errors를 담는다", async () => {
    const errors = [{ field: "heldAssets", reason: "OUT_OF_RANGE", min: 0, max: 10000000, unit: "KRW" }];
    const h = liveWith(() => problemResponse(422, "VALIDATION_FAILED", errors));
    const err = await caught(h.client.request("PUT", "/api/plan", {}));
    expect(err.kind).toBe("problem");
    expect(err.status).toBe(422);
    expect(err.code).toBe("VALIDATION_FAILED");
    expect(err.messageKey).toBeNull();
    expect(err.problem?.errors).toEqual(errors);
    expect(h.calls).toEqual([]);
  });
});

describe("AC-6 목 모드의 4xx·5xx는 실제 fetch와 같은 오류 경로를 탄다", () => {
  it.each([
    ["post-messages.500.json", "POST", "/api/chat/messages", 500, "INTERNAL_ERROR"],
    ["post-apply.409-plan-changed.json", "POST", "/api/chat/messages/1031/apply", 409, "PLAN_CHANGED"],
    ["put-plan.409-plan-changed.json", "PUT", "/api/plan", 409, "PLAN_CHANGED"],
    ["get-comparison.404.json", "GET", "/api/chat/messages/1031/comparison", 404, "NOT_FOUND"],
  ])("%s -> ApiError(status %i, code %s)", async (file, method, path, status, code) => {
    const h = makeHarness({ mode: "mock" });
    useMock(file);
    const err = await caught(h.client.request(method as "GET", path, method === "GET" ? undefined : {}));
    expect(err.kind).toBe("problem");
    expect(err.status).toBe(status);
    expect(err.code).toBe(code);
    expect(h.calls).toEqual([]);
  });

  it("목 모드 성공 응답은 파일의 body를 그대로 돌려준다", async () => {
    const h = makeHarness({ mode: "mock" });
    await expect(h.client.request("GET", "/api/chat/messages?afterId=0")).resolves.toEqual({
      messages: [],
      hasPending: false,
      answerPending: false,
    });
    const undo = readMock("post-undo.200.json").responses[0].body;
    await expect(h.client.request("POST", "/api/plan/undo", { expectedRevision: 13 })).resolves.toEqual(undo);
  });
});

describe("AC-19 입력 저장(P-3) 응답 유실: common.networkError 오류 뒤 loadPlan()으로 revision을 서버에 맞춘다 (D-077)", () => {
  const lost: Array<[string, () => Promise<Response> | Response]> = [
    ["네트워크 오류", () => Promise.reject(new TypeError("Failed to fetch"))],
    ["502", () => htmlResponse(502)],
    ["JSON 아닌 본문(200)", () => htmlResponse(200)],
  ];

  it.each(lost)("PUT /api/plan %s -> 네트워크 오류로 거절하고 loadPlan()을 한 번 부른다", async (_n, impl) => {
    let putSeen = false;
    const h = liveWith((_url, init) => {
      if (init?.method === "PUT") {
        putSeen = true;
        return impl();
      }
      return jsonResponse(200, planView(13)); // 서버에는 저장이 되어 있었다
    });
    const err = await caught(h.client.request("PUT", "/api/plan", { expectedRevision: 12 }));
    expect(putSeen).toBe(true);
    expect(err.kind).toBe("network");
    expect(err.messageKey).toBe("common.networkError");
    await vi.waitFor(() => expect(h.loadPlanSpy).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(h.store.getState().revision).toBe(13));
    const getCalls = h.fetchSpy.mock.calls.filter(([, init]) => init?.method === "GET");
    expect(getCalls.map(([url]) => url)).toEqual(["/api/plan"]);
  });

  it("loadPlan()이 또 실패해도 처리되지 않은 거절을 만들지 않는다", async () => {
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);
    const h = liveWith(() => Promise.reject(new TypeError("Failed to fetch")));
    await caught(h.client.request("PUT", "/api/plan", {}));
    await vi.waitFor(() => expect(h.loadPlanSpy).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    process.off("unhandledRejection", unhandled);
    expect(unhandled).not.toHaveBeenCalled();
  });

  it("그 밖의 요청이 유실돼도 loadPlan()을 부르지 않는다: GET /api/plan(무한 재귀 방지), F-1, P-5", async () => {
    const h = liveWith(() => htmlResponse(502));
    await caught(h.client.request("GET", "/api/plan"));
    await caught(h.client.request("POST", "/api/chat/messages", {}));
    await caught(h.client.request("POST", "/api/plan/undo", { expectedRevision: 12 }));
    await new Promise((r) => setTimeout(r, 20));
    expect(h.loadPlanSpy).not.toHaveBeenCalled();
  });

  it("PUT /api/plan이 정상 problem+json 오류(422·409·500)면 loadPlan()을 부르지 않는다(그 처리는 화면 몫)", async () => {
    for (const [status, code] of [[422, "VALIDATION_FAILED"], [409, "PLAN_CHANGED"], [500, "INTERNAL_ERROR"]] as const) {
      const h = liveWith(() => problemResponse(status, code));
      await caught(h.client.request("PUT", "/api/plan", {}));
      await new Promise((r) => setTimeout(r, 10));
      expect(h.loadPlanSpy).not.toHaveBeenCalled();
    }
  });
});

describe("AC-3·AC-20 로그인·로그아웃 성공 처리", () => {
  it("P-2 로그아웃 200 -> 공유 store 두 칸이 null, 다른 탭에 'logout' 알림", async () => {
    const authSync = { notifyAuthChanged: vi.fn() };
    const h = makeHarness({ mode: "mock", authSync });
    h.store.acceptPlanView(planView(12));
    await h.client.request("POST", "/api/auth/logout");
    expect(h.store.getState()).toMatchObject({ revision: null, view: null });
    expect(authSync.notifyAuthChanged).toHaveBeenCalledTimes(1);
    expect(authSync.notifyAuthChanged).toHaveBeenCalledWith("logout");
  });

  it("P-1 로그인 200 -> 다른 탭에 'login' 알림", async () => {
    const authSync = { notifyAuthChanged: vi.fn() };
    const h = makeHarness({ mode: "mock", authSync });
    await h.client.request("POST", "/api/auth/login", { loginId: "demo", password: "demo" });
    expect(authSync.notifyAuthChanged).toHaveBeenCalledTimes(1);
    expect(authSync.notifyAuthChanged).toHaveBeenCalledWith("login");
  });

  it("로그인 실패(401 INVALID_CREDENTIALS)·로그아웃 401은 성공 알림을 보내지 않는다", async () => {
    const authSync = { notifyAuthChanged: vi.fn() };
    const h = makeHarness({ mode: "mock", authSync, pathname: LOGIN_PATH });
    useMock("post-login.401-invalid.json");
    await caught(h.client.request("POST", "/api/auth/login", {}));
    expect(authSync.notifyAuthChanged).not.toHaveBeenCalled();

    useMock("post-logout.401.json");
    await caught(h.client.request("POST", "/api/auth/logout"));
    expect(authSync.notifyAuthChanged).not.toHaveBeenCalled();
  });
});

describe("AC-11 P-1~P-5 키를 래퍼로 부르면 기본 파일이 온다", () => {
  it("login 200 {}, logout 200 {}, undo 200 {revision}", async () => {
    const h = makeHarness({ mode: "mock" });
    await expect(h.client.request("POST", "/api/auth/login", {})).resolves.toEqual({});
    await expect(h.client.request("POST", "/api/auth/logout")).resolves.toEqual({});
    await expect(h.client.request("POST", "/api/plan/undo", { expectedRevision: 13 })).resolves.toEqual(
      readMock("post-undo.200.json").responses[0].body,
    );
  });
});
