// @vitest-environment jsdom
// Issue #54 AC-14 (검토 plan-2): 설명 완료(hasPending=false)를 받으면 폴링이 멈추고 F-2가 더 나가지 않는다.
import { act, cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred } from "../../../../api/__tests__/helpers";
import { flush, tick } from "../../__tests__/helpers";
import { bubble, chatPage, explanationDone, freshId, makeStore, mountChat, view } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("AC-14 설명 완료 뒤에는 F-2를 더 보내지 않는다 (C12b-5)", () => {
  it("F-3 202로 다시 시작한 폴링이 설명 완료(hasPending=false) 응답을 받으면, 그 호출이 시작될 때의 F-2 수 그대로 10초 동안 늘지 않는다", async () => {
    const x = freshId();
    const f3 = deferred<unknown>();
    const complete = deferred<unknown>();
    const { store } = makeStore();
    store.acceptPlanView(view(12, "CALCULATED", x));
    const { api, f3Bodies } = await mountChat(store, {
      get: [chatPage([], false), complete.promise], // 최초 복원은 대기 없음으로 멈춘다
      post: [f3.promise],
    });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    await act(async () => f3.resolve({}));
    await flush();
    await tick(1000); // 202로 다시 시작한 폴링의 첫 호출이 나가 응답을 기다린다
    const startedAt = api.gets().length;
    expect(startedAt).toBe(2);

    await act(async () => complete.resolve(chatPage([explanationDone(4801, "새 설명입니다")], false)));
    await flush();
    expect(bubble(4801)?.textContent).toContain("새 설명입니다");
    await tick(10_000);
    expect(api.gets()).toHaveLength(startedAt);
  });
});
