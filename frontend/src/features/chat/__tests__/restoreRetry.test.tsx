// @vitest-environment jsdom
// Issue #52 이어서 작업 회귀(AC-6/AC-8): 전체 복원이 한 번도 성공하기 전에 F-1이 먼저 성공해도 이전 대화 복원이 사라지지 않는다.
// 서버는 경로로 답한다(복원=쿼리 없음, 증분=afterId). 호출 순서(복원을 바로 부를지 증분 뒤에 부를지)는 구현 재량이다.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatWindow } from "../index";
import { doneMsg, flush, makeDeps, networkError, page, problem, tick, userMsg, type Reply } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const RESTORE = "/api/chat/messages";
const HISTORY = [userMsg(1029, "이전 질문"), userMsg(1032), doneMsg(1033, "ANSWER", "답")];

/** 경로별로 답하는 가짜 서버. restore는 복원 호출 순서대로 소비하고(끝나면 마지막 값 반복) 증분은 afterId로 만든다. */
function makeServerApi(options: { restore: Reply[]; post: Reply[]; incremental?: (afterId: number) => unknown }) {
  const calls: Array<{ method: string; path: string; body?: unknown }> = [];
  let restoreCursor = 0;
  let postCursor = 0;
  const settle = async (reply: Reply) => {
    const value = await (typeof reply === "function" ? (reply as () => unknown)() : reply);
    if (value instanceof Error) throw value;
    return value;
  };
  const request = vi.fn(async (method: string, path: string, body?: unknown) => {
    calls.push({ method, path, body });
    if (method === "POST") {
      const reply = options.post[Math.min(postCursor, options.post.length - 1)];
      postCursor += 1;
      return settle(reply);
    }
    if (path === RESTORE) {
      const reply = options.restore[Math.min(restoreCursor, options.restore.length - 1)];
      restoreCursor += 1;
      return settle(reply);
    }
    const afterId = Number(new URL(path, "http://x").searchParams.get("afterId"));
    return (options.incremental ?? (() => page([doneMsg(1033, "ANSWER", "답")], false)))(afterId);
  });
  const gets = () => calls.filter((c) => c.method === "GET").map((c) => c.path);
  const restores = () => gets().filter((p) => p === RESTORE);
  return { request, gets, restores, posts: () => calls.filter((c) => c.method === "POST") };
}

const input = () => screen.getByTestId("chat-input") as HTMLTextAreaElement;
const sendButton = () => screen.getByTestId("chat-send") as HTMLButtonElement;
const ask = async (text: string) => {
  fireEvent.change(input(), { target: { value: text } });
  fireEvent.click(sendButton());
  await flush();
};
const ids = () => screen.queryAllByTestId("chat-message").map((el) => Number(el.getAttribute("data-message-id")));

describe("최초 복원 실패 뒤, 재시도 전에 F-1이 성공하는 경우", () => {
  it("202: 아직 받지 못한 이전 대화(1029)를 전체 복원으로 다시 받아 그린다", async () => {
    const api = makeServerApi({
      restore: [networkError(), page(HISTORY, false)],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush(); // 최초 복원이 실패하고 1초 뒤 재시도가 예약된다
    expect(api.restores()).toHaveLength(1);

    await ask("질문"); // 재시도 전에 F-1 202
    await tick(1000);
    await tick(10_000);

    expect(api.restores().length).toBeGreaterThanOrEqual(2); // 성공한 전체 복원이 있어야 한다
    expect(ids()).toEqual([1029, 1032, 1033]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    expect(sendButton().disabled).toBe(true); // 글이 비어 있어서
    fireEvent.change(input(), { target: { value: "다음" } });
    expect(sendButton().disabled).toBe(false); // answerPending=false로 풀림
  });

  it("202 뒤 복원이 끝나면 요청이 더 나가지 않는다(무한 반복 없음)", async () => {
    const api = makeServerApi({
      restore: [networkError(), page(HISTORY, false)],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    await ask("질문");
    await tick(10_000);
    const settled = api.gets().length;
    await tick(30_000);
    expect(api.gets()).toHaveLength(settled);
  });

  it("복원이 성공한 뒤의 다음 F-1은 증분 조회로 간다(복원 필요 상태가 남지 않는다)", async () => {
    const api = makeServerApi({
      restore: [networkError(), page(HISTORY, false)],
      post: [
        { userMessageId: 1032, assistantMessageId: 1033 },
        { userMessageId: 1034, assistantMessageId: 1035 },
      ],
      incremental: (afterId) => page(afterId >= 1034 ? [doneMsg(1035, "ANSWER", "두번째 답")] : [doneMsg(1033, "ANSWER", "답")], false),
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    await ask("질문");
    await tick(10_000);
    expect(ids()).toEqual([1029, 1032, 1033]);

    const restoresBefore = api.restores().length;
    await ask("두번째 질문");
    await tick(10_000);
    expect(api.restores()).toHaveLength(restoresBefore);
    expect(api.gets().at(-1)).toBe("/api/chat/messages?afterId=1034");
    expect(ids()).toEqual([1029, 1032, 1033, 1034, 1035]);
  });

  it("409 ANSWER_PENDING: 복원이 다시 이어져 이전 대화가 그려진다", async () => {
    const api = makeServerApi({
      restore: [networkError(), page([userMsg(1029, "이전 질문"), userMsg(1030), doneMsg(1031, "ANSWER", "답")], false)],
      post: [problem(409, "ANSWER_PENDING")],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    await ask("질문");
    await tick(1000);
    await tick(10_000);
    expect(api.restores().length).toBeGreaterThanOrEqual(2);
    expect(ids()).toEqual([1029, 1030, 1031]);
  });
});

describe("최초 복원이 연속 3회 실패해 멈춘 뒤 F-1이 성공하는 경우", () => {
  it("실패 안내가 사라지고 이전 대화(1029)를 전체 복원으로 받아 그린다", async () => {
    const api = makeServerApi({
      restore: [networkError(), networkError(), networkError(), page(HISTORY, false)],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    await tick(2000);
    expect(screen.getAllByTestId("chat-polling-failed")).toHaveLength(1);
    expect(api.restores()).toHaveLength(3);

    await ask("질문");
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    await tick(1000);
    await tick(10_000);

    expect(api.restores().length).toBeGreaterThanOrEqual(4);
    expect(ids()).toEqual([1029, 1032, 1033]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
  });
});
