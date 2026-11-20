// @vitest-environment jsdom
// 라운드 2 리뷰 B2(AC-15): 이전 되돌리기 요청의 늦은 오류가 새 적용의 되돌리기 토스트를 닫지 않는다. ChatWindow와 진짜 되돌리기 토스트로 확인한다.
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore } from "../../../../api";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { doneMsg, flush, page, pendingMsg, userMsg, tick } from "../../__tests__/helpers";
import { buttonOf, comparison, deferred, makeApi, mountChat, planView } from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const undoButton = () => screen.queryByRole("button", { name: "되돌리기" });

beforeEach(() => {
  vi.useFakeTimers();
  appPlanStore.clear();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  appPlanStore.clear();
});

describe("B2 이전 되돌리기의 늦은 PLAN_CHANGED", () => {
  it("revision 14 토스트는 그 적용 시점부터 10초 동안 유지된다", async () => {
    const undoReply = deferred<Response>();
    let planGets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        if (method === "POST" && url === "/api/plan/undo") return undoReply.promise;
        if (method === "GET" && url === "/api/plan") {
          planGets += 1;
          return jsonResponse(200, planView(planGets === 1 ? 13 : 14));
        }
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([recalc(1033)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1031": [{ revision: 13 }],
      "F5 1033": [{ revision: 14 }],
    });
    await mountChat(api, appPlanStore);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();
    expect(undoButton()).not.toBeNull(); // revision 13 토스트
    fireEvent.click(undoButton() as HTMLElement); // 이전 되돌리기 요청이 보류된다
    await flush();

    await tick(1000); // 1033이 폴링으로 도착
    await flush();
    await flush();
    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    await flush();
    expect(appPlanStore.getState().revision).toBe(14);
    expect(undoButton()).not.toBeNull(); // revision 14 토스트

    await tick(5000);
    undoReply.resolve(problemResponse(409, "PLAN_CHANGED")); // 이전 요청의 늦은 오류
    await flush();
    await flush();
    await flush();
    expect(appPlanStore.getState().revision).toBe(14);
    expect(undoButton(), "이전 요청의 종료가 revision 14 토스트를 닫았다").not.toBeNull();
    await tick(4000); // 14 토스트가 뜬 지 9초 남짓
    expect(undoButton()).not.toBeNull();
    await tick(2000);
    expect(undoButton()).toBeNull();
  });
});
