// @vitest-environment jsdom
// 17차 이후 재개(P-72~P-74): AC-34(두 F-4 응답 전 빈 자리), AC-35(되돌리기 10초 기준 시각), AC-36(이전 되돌리기 응답과 새 토스트).
// 잠긴 테스트와 별도 파일이다. 공개 ChatWindow와 진짜 되돌리기 토스트로 화면만 판정한다.
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore } from "../../../../api";
import { doneMsg, flush, page, pendingMsg, problem, tick, userMsg } from "../../__tests__/helpers";
import { COLLAPSED_LABEL, applyButton, applyButtons, bubble, buttonOf, card, cardOf, comparison, conditionRows, deferred, failedNotice, makeApi, makeStore, mountChat } from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const f4Path = (id: number) => `/api/chat/messages/${id}/comparison`;

beforeEach(() => {
  vi.useFakeTimers();
  appPlanStore.clear();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  appPlanStore.clear();
});

describe("AC-34 두 F-4가 모두 응답 전이면 이전 비교 자리도 비어 있다 (P-72)", () => {
  async function mountBothPending() {
    const f4Old = deferred<unknown>();
    const f4New = deferred<unknown>();
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [f4Old.promise],
      "F4 1033": [f4New.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    await tick(1000); // 폴링: 1033이 RECALC_RESULT로 완료. 두 F-4 모두 응답 전
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
    return { api, f4Old, f4New };
  }

  const expectEmptySlot = () => {
    expect(card(1031), "1031 자리에 카드가 있다").toBeNull();
    expect(conditionRows(1031)).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
    expect(failedNotice(1031)).toBeNull();
    expect(document.body.textContent ?? "").not.toContain(COLLAPSED_LABEL);
    expect(bubble(1031), "본문 말풍선은 그대로 있어야 한다").not.toBeNull();
  };

  it("두 F-4가 응답 전인 동안 1031 자리는 비어 있고, 1031의 F-4가 404로 끝나도 그 자리는 같다", async () => {
    const { f4Old } = await mountBothPending();
    expectEmptySlot();

    f4Old.reject(problem(404, "NOT_FOUND"));
    await flush();
    await flush();
    expectEmptySlot();
  });

  it("대조(AC-8 역순 응답): 1033 F-4가 applicable=true로 먼저 성공하면 1031은 접힌 카드가 되고 활성 버튼은 1033 하나다", async () => {
    const { f4New } = await mountBothPending();

    f4New.resolve(comparison("get-comparison.200.json", { messageId: 1033, applicable: true }));
    await flush();
    await flush();
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
    expect(cardOf(1031).textContent).toContain(COLLAPSED_LABEL);
    expect(applyButton(1031)?.disabled ?? true).toBe(true);
    expect(buttonOf(1033).disabled).toBe(false);
    expect(applyButtons().filter((button) => !button.disabled)).toEqual([buttonOf(1033)]);
  });
});
