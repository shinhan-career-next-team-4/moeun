// @vitest-environment jsdom
// spec.md "제약"의 8차 라운드 지적(B1·B2·B3): 잠긴 수용 테스트가 다루지 않는 경계.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, flush, page, pendingMsg, problem, tick, userMsg } from "../../__tests__/helpers";
import { applyButtons, buttonOf, card, cards, comparison, comparisonToast, deferred, failedNotice, makeApi, makeLiveEnv, makeStore, mountChat, planView } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("B1 F-5 오류 안내는 F-2가 새 RECALC_RESULT를 전달한 시점에만 지운다", () => {
  async function mountPressed(f5: Promise<unknown>) {
    const poll = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), poll.promise],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1031": [f5],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    return { api, poll, store };
  }

  it("새 비교가 도착한 같은 렌더에서 뒤늦게 받은 F-5 오류 안내는 유지된다(F-2가 먼저)", async () => {
    const f5 = deferred<unknown>();
    const { poll } = await mountPressed(f5.promise);
    fireEvent.click(buttonOf(1031));
    await flush();
    await tick(1000);
    await act(async () => {
      poll.resolve(page([recalc(1033)], false));
      for (let i = 0; i < 5; i += 1) await Promise.resolve();
      f5.reject(problem(500, "INTERNAL_ERROR"));
      for (let i = 0; i < 5; i += 1) await Promise.resolve();
    });
    await flush();
    await flush();
    expect(card(1033)).not.toBeNull();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
  });

  it("같은 렌더에서 F-5 오류가 먼저 오고 F-2가 새 비교를 전달하면 안내가 지워진다", async () => {
    const f5 = deferred<unknown>();
    const { poll } = await mountPressed(f5.promise);
    fireEvent.click(buttonOf(1031));
    await flush();
    await tick(1000);
    await act(async () => {
      f5.reject(problem(500, "INTERNAL_ERROR"));
      for (let i = 0; i < 5; i += 1) await Promise.resolve();
      poll.resolve(page([recalc(1033)], false));
      for (let i = 0; i < 5; i += 1) await Promise.resolve();
    });
    await flush();
    await flush();
    expect(card(1033)).not.toBeNull();
    expect(comparisonToast()).toBeNull();
  });
});

describe("B2 changedKeys가 문자열 배열이 아니거나 허용 4키 밖이면 500과 같다(P-59)", () => {
  async function expectLoadFailed(changedKeys: unknown) {
    const body = { ...comparison("get-comparison.200.json"), changedKeys };
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") return json(page([userMsg(1030), recalc(1031)], false));
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") return json(body);
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    await mountChat(env.api, env.store);
    await tick(0);
    await flush();
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    expect(cards()).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
    expect(screen.getByTestId("chat-window")).toBeTruthy();
  }

  it("배열이 아님", async () => {
    await expectLoadFailed("monthlySavingAmount");
  });

  it("문자열이 아닌 항목이 있음", async () => {
    await expectLoadFailed(["monthlySavingAmount", 3]);
  });

  it("허용 4키 밖의 항목이 있음", async () => {
    await expectLoadFailed(["monthlySavingAmount", "unknownKey"]);
  });

  it("키가 없음", async () => {
    await expectLoadFailed(undefined);
  });

  it("허용 4키만 있으면 카드를 그린다(대조)", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, changedKeys: ["targetPeriodMonths", "savingsRatioPct"] })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(card(1031)).not.toBeNull();
    expect(failedNotice(1031)).toBeNull();
  });
});

describe("B3 F-5 200의 revision이 양의 정수가 아니면 네트워크 실패와 같다(P-58)", () => {
  it.each([0, -1])("revision %s", async (revision) => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision }, { revision: 13 }],
    });
    const { store, planGets } = makeStore({ planReplies: [planView(13)] });
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    expect(buttonOf(1031).disabled).toBe(false);
    expect(store.getState().revision).toBe(12);
    expect(planGets).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "되돌리기" })).toBeNull();

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    expect(api.f5Calls()).toHaveLength(2);
    expect(store.getState().revision).toBe(13);
  });
});
