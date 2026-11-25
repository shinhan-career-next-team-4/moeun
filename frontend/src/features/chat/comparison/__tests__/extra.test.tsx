// @vitest-environment jsdom
// spec.md "제약"의 구현 단계 TODO 중 잠긴 테스트가 다루지 않는 것(AC-8·AC-14·AC-16·AC-25).
import { act, cleanup, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, flush, page, pendingMsg, problem, serverError, tick, userMsg } from "../../__tests__/helpers";
import { applyButtons, buttonOf, cardOf, comparison, comparisonToast, conditionRows, deferred, makeApi, makeStore, mountChat, planView } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");

describe("AC-14 요청 중 상태는 메시지별이다", () => {
  it("1031이 먼저 500으로 끝나도 1033 버튼은 자기 요청이 끝날 때까지 비활성이고 F-5가 늘지 않는다", async () => {
    const f5New = deferred<unknown>();
    const f5Old = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([recalc(1033)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1031": [f5Old.promise],
      "F5 1033": [f5New.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    await tick(1000); // 1033 도착, 그 F-4는 applicable=true
    await flush();
    fireEvent.click(buttonOf(1033));
    await flush();
    expect(buttonOf(1033).disabled).toBe(true);

    f5Old.reject(serverError());
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(true);
    fireEvent.click(buttonOf(1033));
    await flush();
    expect(api.f5Calls().map((c) => c.path)).toEqual(["/api/chat/messages/1031/apply", "/api/chat/messages/1033/apply"]);
  });
});

describe("AC-8 연속 비교는 각자 자기 응답 값만 보인다", () => {
  it("1031·1033의 display·changedKeys가 다르면 1033 카드는 1033 값만 보인다", async () => {
    const body1031 = comparison("get-comparison.200.json", { messageId: 1031, changedKeys: ["monthlySavingAmount"] });
    const body1033 = comparison("get-comparison.200.json", { messageId: 1033, changedKeys: ["targetPeriodMonths"] });
    for (const key of Object.keys(body1031.display)) {
      body1031.display[key] = `[1031 ${key}]`;
      body1033.display[key] = `[1033 ${key}]`;
    }
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([recalc(1033)], false)],
      "F4 1031": [body1031],
      "F4 1033": [body1033],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    await tick(1000);
    await flush();
    await flush();
    const text = cardOf(1033).textContent ?? "";
    expect(text).toContain("[1033 conditions.current.monthlySavingAmount]");
    expect(text).not.toContain("[1031 ");
    const changed = conditionRows(1033).filter((row) => row.getAttribute("data-changed") === "true");
    expect(changed.map((row) => row.getAttribute("data-key"))).toEqual(["targetPeriodMonths"]);
  });
});

describe("AC-25 F-5 오류 토스트는 ANSWER·CLARIFY 도착으로는 닫히지 않는다", () => {
  it("새 RECALC_RESULT만 닫고 CLARIFY가 와도 남는다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([doneMsg(1033, "CLARIFY", "어떤 조건인가요?")], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [problem(409, "STALE_COMPARISON")],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
    await tick(1000); // 폴링: 1033이 CLARIFY로 완료
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
  });
});

describe("AC-16 NO_PLAN으로 store를 비운 뒤 늦게 온 이전 F-5 성공", () => {
  it("store를 다시 채우지 않고 되돌리기 토스트·재조회도 만들지 않는다", async () => {
    const late = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [late.promise],
    });
    const { store, planGets } = makeStore({ planReplies: [planView(13)] });
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    act(() => {
      store.acceptNoPlan(12);
    });
    await flush();
    late.resolve({ revision: 13 });
    await flush();
    await flush();
    expect(store.getState().revision).toBeNull();
    expect(planGets).toHaveLength(0);
    expect(document.body.textContent).not.toContain("되돌리기");
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });
});
