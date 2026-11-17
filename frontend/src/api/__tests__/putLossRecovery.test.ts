// 입력 저장(P-3) 응답 유실 복구는 저장 전에 떠난 GET에 합류하지 않는다 (AC-19, AC-16 회귀).
import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "../client";
import { createPlanStore } from "../planStore";
import { deferred, jsonResponse, planView } from "./helpers";

describe("PUT /api/plan 응답 유실 복구", () => {
  it("저장 전에 시작한 GET이 늦게 끝나도 저장 이후 서버 값(rev 13)으로 맞춘다", async () => {
    const holder: { client?: ReturnType<typeof createApiClient> } = {};
    const store = createPlanStore({
      api: { request: (method: "GET", path: string) => holder.client!.request(method, path) },
      navigate: vi.fn(),
    });
    const staleGet = deferred<Response>();
    let gets = 0;
    const fetchSpy = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.reject(new TypeError("Failed to fetch")); // 서버는 rev 13으로 저장함
      gets += 1;
      return gets === 1 ? staleGet.promise : Promise.resolve(jsonResponse(200, planView(13)));
    });
    holder.client = createApiClient({
      mode: "live",
      fetch: fetchSpy as unknown as typeof fetch,
      planStore: store,
      polling: { stopAll: vi.fn() },
      navigate: vi.fn(),
      getPathname: () => "/",
    });

    store.acceptRevision(12);
    const first = store.loadPlan(); // rev 12에서 시작, 응답 지연
    await expect(holder.client.request("PUT", "/api/plan", { expectedRevision: 12 })).rejects.toMatchObject({
      kind: "network",
    });
    await vi.waitFor(() => expect(gets).toBe(2)); // 합류하지 않고 새 GET
    await vi.waitFor(() => expect(store.getState().revision).toBe(13));

    staleGet.resolve(jsonResponse(200, planView(12))); // 늦게 도착한 옛 응답은 값을 되돌리지 못한다
    await first;
    expect(store.getState().revision).toBe(13);
    expect(store.getState().view?.revision).toBe(13);
  });

  it("fresh가 아닌 loadPlan()은 같은 revision의 진행 중 요청에 그대로 합류한다", () => {
    const request = vi.fn(() => new Promise<never>(() => {}));
    const store = createPlanStore({ api: { request: request as never }, navigate: vi.fn() });
    expect(store.loadPlan()).toBe(store.loadPlan());
    expect(request).toHaveBeenCalledTimes(1);
    expect(store.loadPlan({ fresh: true })).not.toBe(store.loadPlan({ fresh: true }));
  });
});
