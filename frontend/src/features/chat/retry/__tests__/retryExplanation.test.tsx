// @vitest-environment jsdom
// Issue #70 FR-AI-05 설명 다시 요청: 같은 F-3(retryOfMessageId)으로 새 설명을 받고, 이미 대기 중이면 다시 보내지 않는다.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, problem, tick } from "../../__tests__/helpers";
import { bubble, chatPage, explanationDone, explanationFailed, explanationPending, freshId, makeStore, mountChat, retryButton, view } from "../../explanation/__tests__/helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const button = () => retryButton() as HTMLButtonElement;

/** 결과가 CALCULATED로 들어오면 자동 F-3이 한 번 나간다. 그 뒤의 요청이 다시 요청이다. */
async function mountWithResult(chat: Parameters<typeof mountChat>[1]) {
  const calcRunId = freshId();
  const { store } = makeStore();
  const mounted = await mountChat(store, chat);
  await act(async () => store.acceptPlanView(view(12, "CALCULATED", calcRunId)));
  await flush();
  return { calcRunId, store, ...mounted };
}

describe("AC-1 실패한 설명을 다시 요청하면 새 설명이 PENDING으로 생긴다", () => {
  it("마지막 설명 메시지 id를 retryOfMessageId로 같은 F-3에 보내고, 새 대기 말풍선을 그린다", async () => {
    let retried = false;
    const { calcRunId, f3Bodies } = await mountWithResult({
      get: [() => (retried ? chatPage([explanationFailed(4503, "설명을 만들지 못했어요"), explanationPending(4504)], true) : chatPage([explanationFailed(4503, "설명을 만들지 못했어요")], false))],
      post: [{}, () => ((retried = true), {})],
    });
    expect(f3Bodies()).toEqual([{ calcRunId }]);

    fireEvent.click(button());
    await flush();
    await tick(1000);

    expect(f3Bodies()).toEqual([{ calcRunId }, { calcRunId, retryOfMessageId: 4503 }]);
    expect(screen.getAllByTestId("chat-pending")).toHaveLength(1);
    expect(bubble(4503)).toBeDefined(); // 원본은 그대로 보인다
  });

  it("완료된 설명도 원본으로 보낸다(불만족 때의 다시 요청)", async () => {
    const { calcRunId, f3Bodies } = await mountWithResult({
      get: [chatPage([explanationDone(4503, "앞 설명")], false)],
      post: [{}, {}],
    });

    fireEvent.click(button());
    await flush();

    // 기록에 설명이 있어 자동 설명은 나가지 않는다(#280). 첫 F-3이 다시 요청이다
    expect(f3Bodies()[0]).toEqual({ calcRunId, retryOfMessageId: 4503 });
  });

  it("보일 설명 메시지가 없으면 retryOfMessageId 없이 calcRunId만 보낸다", async () => {
    const { calcRunId, f3Bodies } = await mountWithResult({ get: [chatPage([], false)], post: [{}, {}] });

    fireEvent.click(button());
    await flush();

    expect(f3Bodies()[1]).toEqual({ calcRunId });
  });
});

describe("AC-2 설명이 PENDING이면 다시 요청이 막힌다", () => {
  it("대기 중인 동안 버튼은 비활성이고 눌러도 요청이 나가지 않는다", async () => {
    const { f3Bodies } = await mountWithResult({
      get: [chatPage([explanationFailed(4503, "실패"), explanationPending(4504)], true)],
      post: [{}, {}],
    });

    expect(button().disabled).toBe(true);
    fireEvent.click(button());
    await flush();

    expect(f3Bodies()).toHaveLength(1);
  });

  it("빠르게 두 번 눌러도 요청은 한 번이다", async () => {
    const { f3Bodies } = await mountWithResult({ get: [chatPage([explanationFailed(4503, "실패")], false)], post: [{}, {}, {}] });

    fireEvent.click(button());
    fireEvent.click(button());
    await flush();

    expect(f3Bodies().filter((body) => (body as { retryOfMessageId?: number }).retryOfMessageId !== undefined)).toHaveLength(1);
  });

  it("서버가 409 ANSWER_PENDING으로 답하면 오류를 그리지 않고 폴링을 이어 서버의 대기 설명을 그린다", async () => {
    let conflicted = false;
    await mountWithResult({
      get: [() => (conflicted ? chatPage([explanationFailed(4503, "실패"), explanationPending(4504)], true) : chatPage([explanationFailed(4503, "실패")], false))],
      post: [{}, () => ((conflicted = true), problem(409, "ANSWER_PENDING"))],
    });

    fireEvent.click(button());
    await flush();
    await tick(1000);

    expect(screen.queryByTestId("chat-toast")).toBeNull();
    expect(screen.getAllByTestId("chat-pending")).toHaveLength(1);
    expect(button().disabled).toBe(true);
  });
});

describe("#224 실패한 설명 아래 [다시 시도]", () => {
  it("실패한 설명(FAILURE) 아래에 보이고, 누르면 '설명 다시 요청'과 같은 F-3을 보낸다", async () => {
    const { calcRunId, f3Bodies } = await mountWithResult({
      get: [chatPage([explanationFailed(4503, "설명을 만들지 못했어요")], false)],
      post: [{}, {}],
    });

    const failureRetry = screen.getByTestId("chat-retry-failure");
    expect(failureRetry.textContent).toBe("다시 시도");
    // 말풍선 본문은 서버 문장 그대로다(단추는 말풍선 밖)
    expect(bubble(4503)?.textContent).toBe("설명을 만들지 못했어요");

    fireEvent.click(failureRetry);
    await flush();

    expect(f3Bodies()).toEqual([{ calcRunId }, { calcRunId, retryOfMessageId: 4503 }]);
  });

  it("완료된 설명에는 [다시 시도]가 없다", async () => {
    await mountWithResult({ get: [chatPage([explanationDone(4503, "앞 설명")], false)], post: [{}] });
    expect(screen.queryByTestId("chat-retry-failure")).toBeNull();
  });
});
