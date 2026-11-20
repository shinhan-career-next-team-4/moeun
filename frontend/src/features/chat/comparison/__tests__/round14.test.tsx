// @vitest-environment jsdom
// 라운드 2 리뷰 B1 회귀(P-72): 최신 비교의 F-4가 500·404로 끝나도 아직 응답 전인 이전 비교(1031) 자리는 비어 있다.
// 잠긴 테스트와 별도 파일이다. 공개 ChatWindow의 화면만 판정한다.
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore } from "../../../../api";
import { doneMsg, flush, page, pendingMsg, problem, tick, userMsg } from "../../__tests__/helpers";
import { COLLAPSED_LABEL, applyButtons, bubble, card, conditionRows, deferred, failedNotice, makeApi, makeStore, mountChat } from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const f4Path = (id: number) => `/api/chat/messages/${id}/comparison`;

beforeEach(() => {
  vi.useFakeTimers();
  appPlanStore.clear();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  appPlanStore.clear();
});

describe("B1 최신 F-4가 실패해도 응답 전인 이전 비교 자리는 비어 있다 (P-72)", () => {
  async function mountBothPending() {
    const f4Old = deferred<unknown>();
    const f4New = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([recalc(1033)], false)],
      "F4 1031": [f4Old.promise],
      "F4 1033": [f4New.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    await tick(1000); // 폴링: 1033이 RECALC_RESULT로 완료. 두 F-4 모두 응답 전
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
    return { f4Old, f4New };
  }

  const expectEmptyOldSlot = () => {
    expect(card(1031), "1031 자리에 카드가 있다").toBeNull();
    expect(conditionRows(1031)).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
    expect(failedNotice(1031)).toBeNull();
    expect(document.body.textContent ?? "").not.toContain(COLLAPSED_LABEL);
    expect(bubble(1031), "본문 말풍선은 그대로 있어야 한다").not.toBeNull();
  };

  it("1033 F-4가 500으로 먼저 끝나면 1033에는 실패 안내가 있고 1031 자리는 비어 있으며, 뒤이은 1031 404 뒤에도 같다", async () => {
    const { f4Old, f4New } = await mountBothPending();

    f4New.reject(problem(500, "INTERNAL_ERROR"));
    await flush();
    await flush();
    expect(failedNotice(1033), "1033에는 실패 안내가 보여야 한다").not.toBeNull();
    expectEmptyOldSlot();
    const markupBefore = document.body.innerHTML;

    f4Old.reject(problem(404, "NOT_FOUND"));
    await flush();
    await flush();
    expect(failedNotice(1033)).not.toBeNull();
    expectEmptyOldSlot();
    expect(document.body.innerHTML, "1031의 404 앞뒤로 화면이 같다").toBe(markupBefore);
  });

  it("1033 F-4가 404로 먼저 끝나도 1031 자리는 비어 있고, 뒤이은 1031 404 뒤에도 같다", async () => {
    const { f4Old, f4New } = await mountBothPending();

    f4New.reject(problem(404, "NOT_FOUND"));
    await flush();
    await flush();
    expectEmptyOldSlot();
    const markupBefore = document.body.innerHTML;

    f4Old.reject(problem(404, "NOT_FOUND"));
    await flush();
    await flush();
    expectEmptyOldSlot();
    expect(document.body.innerHTML).toBe(markupBefore);
  });
});
