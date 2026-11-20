// @vitest-environment jsdom
// Issue #54 AC-14 회귀(구현 검토 B1): 질문 전송(F-1) 직후 떠 있는 증분 F-2와 F-3 202가 겹쳐도 그 응답을 버리지 않는다.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred } from "../../../../api/__tests__/helpers";
import { flush, pollPath, tick } from "../../__tests__/helpers";
import { bubble, chatPage, explanationDone, explanationPending, freshId, makeStore, mountChat, view } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const answer = (id: number, content: string) => explanationDone(id, content, [], "ANSWER");

describe("AC-14 F-1 직후의 증분 F-2와 F-3 202가 겹칠 때 (B1)", () => {
  it("보류한 F-2의 완료 답변을 받는 즉시 반영하고(대기 말풍선 교체), hasPending=false여도 후속 F-2를 보내 새 설명을 받는다", async () => {
    const x = freshId();
    const inFlight = deferred<unknown>();
    const { store } = makeStore();
    const { api, f3Bodies } = await mountChat(store, {
      get: [
        chatPage([], false), // 최초 복원: 대기 없음
        inFlight.promise, // afterId=4501: 질문 답변 완료, hasPending=false
        chatPage([explanationPending(4503)], true),
        chatPage([explanationDone(4503, "새 설명입니다")], false),
      ],
      post: [{ userMessageId: 4501, assistantMessageId: 4502 }, {}],
    });
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "월 50만원으로 하면 어때요?" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await flush();
    await tick(1000); // F-1 뒤 첫 증분 F-2가 나가 응답을 기다린다
    expect(api.gets().map((c) => c.path)).toEqual(["/api/chat/messages", pollPath(4501)]);

    await act(async () => store.acceptPlanView(view(12, "CALCULATED", x)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }]); // F-3 202가 먼저 끝났다
    expect(api.gets()).toHaveLength(2); // 떠 있는 F-2를 버리고 새로 보내지 않는다

    await act(async () => inFlight.resolve(chatPage([answer(4502, "질문에 대한 답")], false)));
    await flush();
    expect(bubble(4502)?.textContent).toContain("질문에 대한 답");
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);

    for (let i = 0; i < 4; i += 1) await tick(1000);
    expect(api.gets().slice(2).map((c) => c.path).every((p) => p === pollPath(4502))).toBe(true);
    expect(api.gets().length).toBeGreaterThanOrEqual(4);
    expect(bubble(4503)?.textContent).toContain("새 설명입니다");
  });
});
