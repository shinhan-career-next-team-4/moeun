// @vitest-environment jsdom
// 라운드 3 리뷰 B1(AC-15): 이전 되돌리기의 늦은 오류가 진행 중인 새 되돌리기 버튼을 다시 활성화하지 않는다. ChatWindow와 진짜 되돌리기 토스트로 확인한다.
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore } from "../../../../api";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { doneMsg, flush, page, pendingMsg, tick, userMsg } from "../../__tests__/helpers";
import { buttonOf, comparison, deferred, makeApi, mountChat, planView } from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const undoButton = () => screen.getByRole("button", { name: "되돌리기" }) as HTMLButtonElement;

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

describe("B1 이전 되돌리기의 늦은 오류와 진행 중인 새 되돌리기", () => {
  it("revision 14의 P-5가 진행 중이면 이전 P-5 오류가 와도 버튼은 비활성이고 요청은 [13,14]다", async () => {
    const undoReplies = [deferred<Response>(), deferred<Response>()];
    const undoBodies: number[] = [];
    let planGets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        if (method === "POST" && url === "/api/plan/undo") {
          const body = JSON.parse(String(init?.body)) as { expectedRevision: number };
          undoBodies.push(body.expectedRevision);
          return undoReplies[undoBodies.length - 1].promise;
        }
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
    fireEvent.click(undoButton()); // revision 13의 P-5가 보류된다
    await flush();

    await tick(1000); // 1033이 폴링으로 도착
    await flush();
    await flush();
    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    await flush();
    expect(appPlanStore.getState().revision).toBe(14);
    fireEvent.click(undoButton()); // revision 14의 P-5가 보류된다
    await flush();
    expect(undoBodies).toEqual([13, 14]);
    expect(undoButton().disabled).toBe(true);

    undoReplies[0].resolve(problemResponse(409, "PLAN_CHANGED")); // 이전 요청의 늦은 오류
    await flush();
    await flush();
    await flush();
    expect(undoButton().disabled, "이전 요청의 오류가 진행 중인 revision 14 버튼을 풀었다").toBe(true);
    fireEvent.click(undoButton());
    await flush();
    expect(undoBodies).toEqual([13, 14]);
  });
});
