// @vitest-environment jsdom
// spec.md "제약"의 7차 라운드 지적(B1·B2·B3·B4·B5): 잠긴 수용 테스트가 다루지 않는 경계.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, flush, page, pendingMsg, problem, tick, userMsg } from "../../__tests__/helpers";
import {
  applyButtons,
  buttonOf,
  comparisonToast,
  card,
  cards,
  comparison,
  deferred,
  failedNotice,
  makeApi,
  makeLiveEnv,
  makeStore,
  mountChat,
  planView,
} from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");

describe("B1·B2 F-2 도착과 revision 변경이 같은 렌더에 합쳐지면", () => {
  it("그 렌더에 도착한 새 비교(1033)는 revision 변경 전에 있던 것으로 보아 비활성이다", async () => {
    const poll = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), poll.promise],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    await tick(1000); // 폴링이 나가고 응답 대기
    // 도착한 뒤 React가 그리기 전에 revision이 바뀐다(act가 렌더를 끝까지 미룬다).
    await act(async () => {
      poll.resolve(page([recalc(1033)], false));
      for (let i = 0; i < 5; i += 1) await Promise.resolve();
      store.acceptRevision(13);
    });
    await flush();
    await flush();
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
    expect(card(1033)).not.toBeNull();
    expect(applyButtons().filter((button) => !button.disabled)).toHaveLength(0);
  });

  it("revision이 바뀐 뒤 따로 도착한 새 비교는 F-4 applicable=true면 활성이다(대조)", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([recalc(1033)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    act(() => {
      store.acceptRevision(13);
    });
    await flush();
    await tick(1000);
    await flush();
    await flush();
    expect(applyButtons().filter((button) => !button.disabled)).toHaveLength(1);
  });
});

describe("B3·B4 카드에 필요한 표시값이 없는 F-4 200은 500과 같다(P-59)", () => {
  async function expectLoadFailed(mutate: (body: ReturnType<typeof comparison>) => void) {
    const body = comparison("get-comparison.200.json");
    mutate(body);
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") {
        return new Response(JSON.stringify(page([userMsg(1030), recalc(1031)], false)), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") {
        return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
      }
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

  it("조건 행의 현재 값 표시값이 없음", async () => {
    await expectLoadFailed((body) => {
      delete body.display["conditions.current.monthlySavingAmount"];
    });
  });

  it("조건 행의 바꾼 값 표시값이 문자열이 아님", async () => {
    await expectLoadFailed((body) => {
      (body.display as Record<string, unknown>)["conditions.changed.targetPeriodMonths"] = null;
    });
  });

  it("시나리오의 자산 차이 표시값이 없음", async () => {
    await expectLoadFailed((body) => {
      delete body.display["diff.AVG.afterTaxAssetDelta"];
    });
  });

  it("시나리오의 달성 변화가 문자열이 아님", async () => {
    await expectLoadFailed((body) => {
      (body.diff.byScenario[0] as Record<string, unknown>).achievementChange = null;
    });
  });
});

describe("B5 NO_PLAN으로 비운 뒤 새 계획이 채워져도 늦게 온 이전 F-5 성공은 덮지 않는다", () => {
  it("null -> 1 뒤 {revision: 13}이 와도 store는 revision 1이고 토스트·재조회가 없다", async () => {
    const late = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [late.promise],
    });
    const { store, planGets } = makeStore({ planReplies: [planView(13)] });
    await mountChat(api, store);
    const button = applyButtons()[0];
    act(() => button.click());
    await flush();
    act(() => {
      store.acceptNoPlan(12);
    });
    act(() => {
      store.acceptPlanView(planView(1));
    });
    await flush();
    late.resolve({ revision: 13 });
    await flush();
    await flush();
    expect(store.getState().revision).toBe(1);
    expect(planGets).toHaveLength(0);
    expect(document.body.textContent).not.toContain("되돌리기");
    for (const each of applyButtons()) expect(each.disabled).toBe(true);
  });
});

describe("AC-25 F-5 오류 토스트는 ANSWER 도착으로 닫히지 않는다", () => {
  it("새 RECALC_RESULT만 닫고 ANSWER가 와도 남는다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), page([doneMsg(1033, "ANSWER", "그건 계산에 쓰지 않아요")], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [problem(409, "STALE_COMPARISON")],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
    await tick(1000); // 폴링: 1033이 ANSWER로 완료
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
  });
});
