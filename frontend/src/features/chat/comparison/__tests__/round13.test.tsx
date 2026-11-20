// @vitest-environment jsdom
// 17차 이후 재개(P-72~P-74): AC-34(두 F-4 응답 전 빈 자리), AC-35(되돌리기 10초 기준 시각), AC-36(이전 되돌리기 응답과 새 토스트).
// 잠긴 테스트와 별도 파일이다. 공개 ChatWindow와 진짜 되돌리기 토스트로 화면만 판정한다.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore } from "../../../../api";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { ChatWindow } from "../../index";
import { doneMsg, flush, page, pendingMsg, problem, tick, userMsg } from "../../__tests__/helpers";
import {
  COLLAPSED_LABEL,
  applyButton,
  applyButtons,
  bubble,
  buttonOf,
  card,
  cardOf,
  comparison,
  conditionRows,
  deferred,
  failedNotice,
  makeApi,
  makeStore,
  mountChat,
  planView,
} from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const f4Path = (id: number) => `/api/chat/messages/${id}/comparison`;
const undoButton = () => screen.queryByRole("button", { name: "되돌리기" });

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

describe("AC-35 되돌리기 10초는 F-5 성공 반영 시점부터 센다 (P-73)", () => {
  it("재조회 시작이 동기적으로 400ms 늦어지고 다시 렌더링되어도 9.9초에 있고 10.1초에 없다", async () => {
    const env = makeStore();
    let successAt = -1;
    env.store.subscribe((state, prev) => {
      if (state.revision === 13 && prev.revision !== 13) successAt = Date.now();
    });
    const original = env.store.loadPlan.bind(env.store);
    vi.spyOn(env.store, "loadPlan").mockImplementation((...args: Parameters<typeof original>) => {
      vi.advanceTimersByTime(400); // 재조회 시작 쪽의 동기 지연
      return original(...args);
    });
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [{ revision: 14 }],
    });
    const { deps, view } = await mountChat(api, env.store);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();
    expect(successAt).toBeGreaterThan(0);
    expect(undoButton()).not.toBeNull();

    await tick(successAt + 5_000 - Date.now());
    view.rerender(<ChatWindow deps={{ ...deps }} />); // 다시 렌더링해도 만료 시각이 밀리지 않는다
    await flush();
    expect(undoButton()).not.toBeNull();

    await tick(successAt + 9_900 - Date.now());
    expect(undoButton(), "성공 시점부터 9.9초에 되돌리기 버튼이 없다").not.toBeNull();
    await tick(successAt + 10_100 - Date.now());
    expect(undoButton(), "성공 시점부터 10.1초에 되돌리기 버튼이 남아 있다").toBeNull();
  });
});

describe("AC-36 이전 되돌리기 응답은 새 적용의 토스트를 닫지 않는다 (P-74)", () => {
  /** 1031 적용(1→2) → 되돌리기 응답 보류 → 계획 비워짐 → 새 계획 1 → 새 1033 적용(1→2). */
  async function reachSecondToast() {
    const oldUndo = deferred<Response>();
    const poll = deferred<unknown>();
    const undoBodies: unknown[] = [];
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
    return { oldUndo, undoBodies, secondAt };
  }

  it("이전 P-5가 409 NOTHING_TO_UNDO로 끝나고 재조회가 revision 2를 돌려줘도 새 토스트는 새 적용 성공부터 10초 보인다", async () => {
    const { oldUndo, secondAt } = await reachSecondToast();

    await tick(2_000);
    oldUndo.resolve(problemResponse(409, "NOTHING_TO_UNDO"));
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

    oldUndo.resolve(problemResponse(409, "NOTHING_TO_UNDO"));
    await flush();
    await flush();
    await flush();
    fireEvent.click(undoButton() as HTMLElement);
    await flush();
    await flush();
    expect(undoBodies).toEqual([{ expectedRevision: 2 }, { expectedRevision: 2 }]);
  });
});
