// @vitest-environment jsdom
// #390: 비교 카드(F-4)가 답보다 늦게 그려져도, 맨 아래에 있던 대화창은 카드 아래(적용 버튼)까지 다시 내려간다.
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { doneMsg, flush, page, userMsg } from "../../__tests__/helpers";
import { comparison, deferred, makeApi, makeStore, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("카드가 늦게 뜬 뒤 스크롤", () => {
  it("맨 아래에 있으면 카드가 그려져 높이가 늘어난 뒤 다시 맨 아래로 내려간다", async () => {
    const lateF4 = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), doneMsg(1031, "RECALC_RESULT", "목표를 바꿔 보면 평균 기준 모자라요")], false)],
      "F4 1031": [lateF4.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    const box = document.querySelector('[data-testid="chat-window"] .overflow-y-auto') as HTMLElement;
    Object.defineProperty(box, "scrollHeight", { configurable: true, get: () => height });
    let height = 900;
    box.scrollTop = 900;

    // 카드가 그려져 높이가 늘어난다
    height = 1300;
    lateF4.resolve(comparison("get-comparison.200.json", { messageId: 1031, applicable: true }));
    await flush();
    await flush();

    expect(box.scrollTop).toBe(1300);
  });
});
