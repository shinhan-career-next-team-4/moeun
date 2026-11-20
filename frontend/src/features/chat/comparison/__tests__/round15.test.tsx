// @vitest-environment jsdom
// 재개(P-75): AC-37 폐기된 되돌리기의 늦은 NO_PLAN은 같은 계정의 새 계획·새 토스트를 건드리지 않는다.
// 잠긴 테스트와 별도 파일이다. 공개 ChatWindow와 진짜 되돌리기 토스트로 화면만 판정한다.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore, setNavigate } from "../../../../api";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { INPUT_PATH } from "../../../../app/routes";
import { doneMsg, flush, page, pendingMsg, tick, userMsg } from "../../__tests__/helpers";
import { buttonOf, comparison, deferred, makeApi, mountChat, planView } from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const undoButton = () => screen.queryByRole("button", { name: "되돌리기" });

beforeEach(() => {
  vi.useFakeTimers();
  appPlanStore.clear();
});

afterEach(() => {
  cleanup();
  setNavigate(null);
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  appPlanStore.clear();
});

describe("AC-37 폐기된 되돌리기의 늦은 NO_PLAN은 같은 계정의 새 계획·새 토스트를 건드리지 않는다 (P-75)", () => {
  /** 1031 적용(1→2) → 되돌리기 응답 보류 → 계획 비워짐(토스트 폐기) → 새 계획 1 → 새 1033 적용(1→2). */
  async function reachSecondToast() {
    const oldUndo = deferred<Response>();
    const poll = deferred<unknown>();
    const undoBodies: unknown[] = [];
    const navigations: string[] = [];
    setNavigate((to) => navigations.push(to));
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        if (method === "POST" && url === "/api/plan/undo") {
          undoBodies.push(JSON.parse(String(init?.body)));
          return undoBodies.length === 1 ? oldUndo.promise : jsonResponse(200, { revision: 3 });
        }
        if (method === "GET" && url === "/api/plan") return jsonResponse(200, planView(2));
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(1));
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), poll.promise],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1031": [{ revision: 2 }],
      "F5 1033": [{ revision: 2 }],
    });
    await mountChat(api, appPlanStore);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();
    expect(appPlanStore.getState().revision).toBe(2);
    fireEvent.click(undoButton() as HTMLElement); // 이전 P-5가 보류된다
    await flush();
    expect(undoBodies).toEqual([{ expectedRevision: 2 }]);

    await tick(1000); // 폴링 요청이 나가고 응답 대기
    await act(async () => {
      appPlanStore.acceptNoPlan(2);
    });
    await act(async () => {
      appPlanStore.acceptPlanView(planView(1)); // 새 계획이 revision을 다시 1부터 쓴다
    });
    poll.resolve(page([recalc(1033)], false));
    await flush();
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);

    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    await flush();
    expect(appPlanStore.getState().revision).toBe(2);
    expect(undoButton(), "새 적용의 되돌리기 토스트가 없다").not.toBeNull();
    const secondAt = Date.now();
    navigations.length = 0; // 이전 비우기로 생긴 이동은 세지 않는다
    return { oldUndo, undoBodies, navigations, secondAt };
  }

  it("이전 P-5가 409 NO_PLAN으로 늦게 끝나도 store revision은 2 그대로이고 입력 화면으로 가지 않는다", async () => {
    const { oldUndo, navigations } = await reachSecondToast();

    await tick(2_000);
    oldUndo.resolve(problemResponse(409, "NO_PLAN"));
    await flush();
    await flush();
    await flush();
    expect(appPlanStore.getState().revision, "늦은 NO_PLAN이 새 계획을 비웠다").toBe(2);
    expect(appPlanStore.getState().view).not.toBeNull();
    expect(navigations).not.toContain(INPUT_PATH);
  });

  it("이전 P-5가 409 NO_PLAN으로 늦게 끝나도 새 토스트는 새 적용 성공부터 10초 보인다(9.9초에 있고 10.1초에 없다)", async () => {
    const { oldUndo, secondAt } = await reachSecondToast();

    await tick(2_000);
    oldUndo.resolve(problemResponse(409, "NO_PLAN"));
    await flush();
    await flush();
    await flush();
    expect(undoButton(), "이전 요청의 종료가 새 토스트를 닫았다").not.toBeNull();

    await tick(secondAt + 9_900 - Date.now());
    expect(undoButton()).not.toBeNull();
    await tick(secondAt + 10_100 - Date.now());
    expect(undoButton()).toBeNull();
  });

  it("새 토스트의 되돌리기 요청 본문은 그 토스트의 revision({expectedRevision: 2})이다", async () => {
    const { oldUndo, undoBodies } = await reachSecondToast();

    oldUndo.resolve(problemResponse(409, "NO_PLAN"));
    await flush();
    await flush();
    await flush();
    fireEvent.click(undoButton() as HTMLElement);
    await flush();
    await flush();
    expect(undoBodies).toEqual([{ expectedRevision: 2 }, { expectedRevision: 2 }]);
  });
});
