// AC-3(폴링 멈춤 수단), AC-20: 탭 사이 로그인·로그아웃 동기화(D-078)와 폴링 레지스트리.
import { describe, expect, it, vi } from "vitest";
import { AUTH_CHANNEL_NAME, createAuthSync } from "../authSync";
import { createPollingRegistry } from "../polling";
import { jsonResponse, makeHarness, planView } from "./helpers";

// 같은 이름의 채널끼리 메시지를 주고받는 가짜 BroadcastChannel.
// 실제 규격처럼 보낸 객체 자신에게는 전달하지 않고, 같은 이름의 다른 객체에만 전달한다.
class FakeBus {
  channels = new Set<FakeChannel>();
  names: string[] = [];
  create = (name: string) => {
    this.names.push(name);
    return new FakeChannel(name, this);
  };
}
class FakeChannel {
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  private listeners = new Set<(ev: { data: unknown }) => void>();
  closed = false;
  constructor(
    readonly name: string,
    private bus: FakeBus,
  ) {
    bus.channels.add(this);
  }
  postMessage(data: unknown) {
    for (const ch of this.bus.channels) {
      if (ch === this || ch.closed || ch.name !== this.name) continue;
      const ev = { data };
      ch.onmessage?.(ev);
      ch.listeners.forEach((l) => l(ev));
    }
  }
  addEventListener(_type: "message", l: (ev: { data: unknown }) => void) {
    this.listeners.add(l);
  }
  removeEventListener(_type: "message", l: (ev: { data: unknown }) => void) {
    this.listeners.delete(l);
  }
  close() {
    this.closed = true;
    this.bus.channels.delete(this);
  }
}

/** 탭 하나 = store 하나 + authSync 하나. 요청 순간의 store 상태를 기록한다. */
function tab(bus: FakeBus, fetchImpl: () => Response) {
  const seenAtRequest: Array<{ revision: number | null }> = [];
  const fetchSpy = vi.fn(async () => {
    seenAtRequest.push({ revision: h.store.getState().revision });
    return fetchImpl();
  });
  const h = makeHarness({ mode: "live", fetch: fetchSpy as unknown as typeof fetch });
  const sync = createAuthSync({ store: h.store, createChannel: bus.create as never });
  return { ...h, sync, fetchSpy, seenAtRequest };
}

describe("AC-20 BroadcastChannel('moeun-auth')", () => {
  it("채널 이름은 moeun-auth 이다", () => {
    expect(AUTH_CHANNEL_NAME).toBe("moeun-auth");
    const bus = new FakeBus();
    const t = tab(bus, () => jsonResponse(200, planView(12)));
    expect(bus.names).toEqual(["moeun-auth"]);
    t.sync.dispose();
  });

  it.each(["login", "logout"] as const)(
    "다른 탭에서 %s 성공 알림을 받으면 store를 비운 뒤 loadPlan()부터 다시 시작한다",
    async (kind) => {
      const bus = new FakeBus();
      const a = tab(bus, () => jsonResponse(200, planView(20)));
      const b = tab(bus, () => jsonResponse(200, planView(20)));
      b.store.acceptPlanView(planView(12));

      a.sync.notifyAuthChanged(kind);

      await vi.waitFor(() => expect(b.fetchSpy).toHaveBeenCalledTimes(1));
      // 요청이 나가는 시점에는 이미 비워져 있다(옛 revision을 들고 있지 않는다)
      expect(b.seenAtRequest[0].revision).toBeNull();
      const [url, init] = b.fetchSpy.mock.calls[0] as unknown as [string, RequestInit];
      expect(url).toBe("/api/plan");
      expect(init.method).toBe("GET");
      await vi.waitFor(() => expect(b.store.getState().revision).toBe(20));
    },
  );

  it("알림을 보낸 탭 자신은 비우지도, loadPlan()을 부르지도 않는다", async () => {
    const bus = new FakeBus();
    const a = tab(bus, () => jsonResponse(200, planView(20)));
    tab(bus, () => jsonResponse(200, planView(20)));
    a.store.acceptPlanView(planView(12));
    a.sync.notifyAuthChanged("logout");
    await new Promise((r) => setTimeout(r, 20));
    expect(a.fetchSpy).not.toHaveBeenCalled();
    expect(a.store.getState().revision).toBe(12);
  });

  it("받은 탭의 loadPlan()이 실패해도 처리되지 않은 거절을 만들지 않는다", async () => {
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);
    const bus = new FakeBus();
    const a = tab(bus, () => jsonResponse(200, planView(1)));
    const b = tab(bus, () => {
      throw new TypeError("Failed to fetch");
    });
    a.sync.notifyAuthChanged("login");
    await vi.waitFor(() => expect(b.fetchSpy).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    process.off("unhandledRejection", unhandled);
    expect(unhandled).not.toHaveBeenCalled();
  });

  it("dispose() 뒤에는 알림에 반응하지 않는다", async () => {
    const bus = new FakeBus();
    const a = tab(bus, () => jsonResponse(200, planView(20)));
    const b = tab(bus, () => jsonResponse(200, planView(20)));
    b.store.acceptPlanView(planView(12));
    b.sync.dispose();
    a.sync.notifyAuthChanged("logout");
    await new Promise((r) => setTimeout(r, 20));
    expect(b.fetchSpy).not.toHaveBeenCalled();
    expect(b.store.getState().revision).toBe(12);
  });

  it("래퍼는 P-1·P-2 성공 때 authSync로 알려 다른 탭이 반응한다(끝에서 끝까지)", async () => {
    const bus = new FakeBus();
    const b = tab(bus, () => jsonResponse(200, planView(20)));
    const holder = { notify: (_k: "login" | "logout") => {} };
    const a = makeHarness({
      mode: "mock",
      authSync: { notifyAuthChanged: (k) => holder.notify(k) },
    });
    const aSync = createAuthSync({ store: a.store, createChannel: bus.create as never });
    holder.notify = aSync.notifyAuthChanged;
    b.store.acceptPlanView(planView(12));

    await a.client.request("POST", "/api/auth/logout");

    await vi.waitFor(() => expect(b.fetchSpy).toHaveBeenCalledTimes(1));
    expect(b.seenAtRequest[0].revision).toBeNull();
  });
});

describe("AC-3 폴링 레지스트리: UNAUTHENTICATED 때 폴링을 멈추는 수단", () => {
  it("등록한 중단 함수를 stopAll()이 모두 한 번씩 부르고, 해제한 것은 부르지 않는다", () => {
    const reg = createPollingRegistry();
    const a = vi.fn();
    const b = vi.fn();
    const c = vi.fn();
    reg.register(a);
    const unregisterB = reg.register(b);
    reg.register(c);
    unregisterB();
    reg.stopAll();
    expect(a).toHaveBeenCalledTimes(1);
    expect(b).not.toHaveBeenCalled();
    expect(c).toHaveBeenCalledTimes(1);
    reg.stopAll(); // 이미 멈춘 것은 다시 부르지 않는다
    expect(a).toHaveBeenCalledTimes(1);
  });

  it("아무것도 없을 때 stopAll()은 아무 일도 하지 않는다", () => {
    expect(() => createPollingRegistry().stopAll()).not.toThrow();
  });
});
