// 인증 변경(clear) 중에 진행 중이던 GET이 이전 인증의 계획을 되살리지 않는다 (AC-16, AC-20 회귀).
import { describe, expect, it, vi } from "vitest";
import { createPlanStore, type PlanView } from "../planStore";
import { deferred, planView } from "./helpers";

function setup() {
  const pending: Array<ReturnType<typeof deferred<PlanView>>> = [];
  const request = vi.fn(() => {
    const d = deferred<PlanView>();
    pending.push(d);
    return d.promise;
  });
  const store = createPlanStore({ api: { request: request as never }, navigate: vi.fn() });
  return { store, request, pending };
}

describe("인증 변경 중 진행 중인 loadPlan", () => {
  it("revision이 null일 때 시작된 GET에는 clear 뒤 합류하지 않고 새로 보낸다", async () => {
    const { store, request, pending } = setup();
    const first = store.loadPlan();
    store.clear(); // 다른 탭의 로그아웃·계정 전환
    const second = store.loadPlan();
    expect(second).not.toBe(first);
    expect(request).toHaveBeenCalledTimes(2);

    pending[1].resolve(planView(1));
    await second;
    pending[0].resolve(planView(12)); // 이전 인증의 늦은 응답은 버려지고 현재 인증으로 다시 조회한다
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(3));
    pending[2].resolve(planView(1));
    await first;
    expect(store.getState().revision).toBe(1);
    expect(store.getState().view?.revision).toBe(1);
  });

  it("clear 전에 시작한 GET의 응답은 store에 반영되지 않고, 새 인증으로 다시 조회한다", async () => {
    const { store, request, pending } = setup();
    const first = store.loadPlan();
    store.clear();
    pending[0].resolve(planView(12));
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(store.getState()).toEqual({ revision: null, view: null, settledStatus: null });

    pending[1].resolve(planView(1));
    expect((await first)?.revision).toBe(1);
    expect(store.getState().revision).toBe(1);
  });

  it("rev12 상태에서 시작한 GET이 clear 뒤 새 GET(rev1)보다 늦게 끝나도 rev12로 덮이지 않는다", async () => {
    const { store, request, pending } = setup();
    store.acceptPlanView(planView(12));
    const first = store.loadPlan();
    store.clear();
    const second = store.loadPlan();
    pending[1].resolve(planView(1));
    await second;
    pending[0].resolve(planView(12));
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(3));
    pending[2].resolve(planView(1));
    await first;
    expect(store.getState().revision).toBe(1);
    expect(store.getState().view?.revision).toBe(1);
  });
});
