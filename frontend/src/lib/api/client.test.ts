import { ApiError, apiFetch } from "./client";

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("apiFetch", () => {
  const assign = vi.fn();

  beforeEach(() => {
    assign.mockReset();
    vi.stubGlobal("location", { ...window.location, assign });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("credentials include로 JSON을 보낸다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiFetch<{ ok: boolean }>("/api/plan", { method: "POST", body: { a: 1 } });

    expect(result).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/plan");
    expect(init.credentials).toBe("include");
    expect(init.method).toBe("POST");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.body).toBe(JSON.stringify({ a: 1 }));
  });

  it("400이면 errors를 담은 ApiError를 던진다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(400, { detail: "입력값을 확인해 주세요.", errors: [{ field: "goalAmount", code: "REQUIRED" }] }),
      ),
    );

    const error = await apiFetch("/api/plan").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).errors).toEqual([{ field: "goalAmount", code: "REQUIRED" }]);
  });

  it("409면 ApiError.plan에 현재 계획을 담는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(409, { plan: { revision: 3 } })));

    const error = (await apiFetch("/api/plan/apply").catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(409);
    expect(error.plan).toEqual({ revision: 3 });
  });

  it("401이면 /login?expired=1로 이동한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));

    await expect(apiFetch("/api/plan")).rejects.toBeInstanceOf(ApiError);
    expect(assign).toHaveBeenCalledWith("/login?expired=1");
  });

  it("skipAuthRedirect면 401에도 이동하지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));

    await expect(apiFetch("/api/auth/me", { skipAuthRedirect: true })).rejects.toBeInstanceOf(ApiError);
    expect(assign).not.toHaveBeenCalled();
  });

  it("204면 undefined를 돌려준다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await expect(apiFetch("/api/auth/logout", { method: "POST" })).resolves.toBeUndefined();
  });

  it("네트워크 오류는 status 0인 ApiError다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    const error = (await apiFetch("/api/plan").catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(0);
  });
});
