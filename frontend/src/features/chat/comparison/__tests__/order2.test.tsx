// @vitest-environment jsdom
// 라운드 2 리뷰 지적: B1(완료와 revision 변경의 실제 순서, AC-21·P-29), B2(필수 시나리오 데이터가 틀린 F-4 200, AC-13·P-59).
import { act, cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, flush, page, pendingMsg, tick, userMsg } from "../../__tests__/helpers";
import { applyButtons, applyButton, card, cards, comparison, deferred, failedNotice, makeApi, makeLiveEnv, makeStore, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");

describe("B1 완료와 revision 변경이 같은 렌더에 합쳐져도 실제 순서로 판정한다", () => {
  async function run(order: "completionFirst" | "revisionFirst") {
    const poll = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true), poll.promise],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    await tick(1000);
    await act(async () => {
      poll.resolve(page([recalc(1033)], false));
      // 폴러의 await가 재개되기 전(revisionFirst)이냐 뒤(completionFirst)냐만 다르다. React는 act가 끝날 때까지 그리지 않는다.
      if (order === "completionFirst") for (let i = 0; i < 5; i += 1) await Promise.resolve();
      store.acceptRevision(13);
    });
    await flush();
    await flush();
    return api;
  }

  it("완료가 먼저면 1033은 비활성이다", async () => {
    const api = await run("completionFirst");
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
    expect(applyButtons().filter((button) => !button.disabled)).toHaveLength(0);
  });

  it("revision이 먼저면 1033은 F-4 applicable=true대로 활성이고 1031은 비활성이다", async () => {
    const api = await run("revisionFirst");
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
    expect(applyButton(1033)?.disabled).toBe(false);
    expect(applyButtons().filter((button) => !button.disabled)).toHaveLength(1);
  });
});

describe("B2 카드에 필요한 시나리오 데이터가 틀린 F-4 200은 500과 같다(P-59)", () => {
  async function expectLoadFailed(mutate: (body: ReturnType<typeof comparison>) => void) {
    const body = comparison("get-comparison.200.json");
    mutate(body);
    const env = makeLiveEnv((method, requestPath) => {
      const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });
      if (method === "GET" && requestPath === "/api/chat/messages") return json(page([userMsg(1030), recalc(1031)], false));
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") return json(body);
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    await mountChat(env.api, env.store);
    await tick(0);
    await flush();
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    expect(cards()).toHaveLength(0);
    expect(card(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(0);
  }

  it("diff.byScenario가 빈 배열", async () => {
    await expectLoadFailed((body) => {
      (body.diff as { byScenario: unknown[] }).byScenario = [];
    });
  });

  it("달성 변화가 알 수 없는 값(UNKNOWN)", async () => {
    await expectLoadFailed((body) => {
      (body.diff as { byScenario: { achievementChange: string }[] }).byScenario[0].achievementChange = "UNKNOWN";
    });
  });
});
