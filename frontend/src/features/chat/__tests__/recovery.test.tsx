// @vitest-environment jsdom
// Issue #52 라운드 2 회귀: 202 뒤 폴링 실패 복구(AC-8), 복원 응답보다 202가 먼저 오는 경우(AC-6).
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred } from "../../../api/__tests__/helpers";
import { ChatWindow } from "../index";
import { doneMsg, flush, makeDeps, makeFakeApi, networkError, page, pendingMsg, pollPath, tick, userMsg } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const input = () => screen.getByTestId("chat-input") as HTMLTextAreaElement;
const sendButton = () => screen.getByTestId("chat-send") as HTMLButtonElement;
const type = (value: string) => fireEvent.change(input(), { target: { value } });
const send = async () => {
  fireEvent.click(sendButton());
  await flush();
};
const ids = () => screen.queryAllByTestId("chat-message").map((el) => Number(el.getAttribute("data-message-id")));

describe("202 뒤 폴링이 3회 실패한 경우", () => {
  it("실패 안내 뒤에도 질문을 다시 보낼 수 있고 폴링이 재개된다", async () => {
    const api = makeFakeApi({
      get: [page([], false), networkError(), networkError(), networkError(), page([doneMsg(1033, "ANSWER", "답")], false)],
      post: [
        { userMessageId: 1030, assistantMessageId: 1031 },
        { userMessageId: 1032, assistantMessageId: 1033 },
      ],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();

    type("첫 질문");
    await send();
    await tick(3000);
    expect(screen.getAllByTestId("chat-polling-failed")).toHaveLength(1);
    expect(screen.getAllByTestId("chat-pending")).toHaveLength(1);
    expect(sendButton().disabled).toBe(false); // 단추는 늘 눌러 볼 수 있다(빈 글이면 보내지 않고 안내, #423)

    type("다시 질문");
    expect(sendButton().disabled).toBe(false);
    await send();
    expect(api.posts()).toHaveLength(2);
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    await tick(1000);
    expect(api.gets().at(-1)?.path).toBe(pollPath(1030));
  });
});

describe("202가 최초 복원 응답보다 먼저 오는 경우", () => {
  it("늦게 온 이전 대화가 새 질문·답변과 함께 남는다", async () => {
    const restore = deferred<unknown>();
    const api = makeFakeApi({
      get: [() => restore.promise, page([doneMsg(1033, "ANSWER", "답")], false)],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();

    type("질문");
    await send();
    restore.resolve(page([userMsg(1028), doneMsg(1029, "ANSWER", "이전 답")], false));
    await flush();
    await tick(1000);
    await tick(10_000);

    expect(ids()).toEqual([1028, 1029, 1032, 1033]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(api.gets().map((c) => c.path)).toEqual(["/api/chat/messages", pollPath(1032)]);
  });

  it("늦게 온 복원 응답이 이미 가진 id를 옛 값으로 덮어쓰지 않는다", async () => {
    const restore = deferred<unknown>();
    const api = makeFakeApi({
      get: [() => restore.promise, page([doneMsg(1033, "ANSWER", "답")], false)],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    type("질문");
    await send();
    await tick(1000);
    restore.resolve(page([userMsg(1029), pendingMsg(1033)], true));
    await flush();
    expect(ids()).toEqual([1029, 1032, 1033]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
  });

  it("최초 복원이 실패하면 다음 호출에서 복원을 다시 받는다", async () => {
    const restore = deferred<unknown>();
    const api = makeFakeApi({
      get: [() => restore.promise, page([userMsg(1029), userMsg(1032), doneMsg(1033, "ANSWER", "답")], false)],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    type("질문");
    await send();
    restore.reject(networkError());
    await flush();
    await tick(1000);
    expect(api.gets().map((c) => c.path)).toEqual(["/api/chat/messages", "/api/chat/messages"]);
    expect(ids()).toEqual([1029, 1032, 1033]);
  });
  it("후속 조회가 끝난 뒤 과거 PENDING을 담은 복원이 오면 안전장치 복원을 한 번 보낸다", async () => {
    const restore = deferred<unknown>();
    const api = makeFakeApi({
      get: [
        () => restore.promise,
        page([doneMsg(1033, "ANSWER", "답")], false),
        page([doneMsg(1029, "EXPLANATION", "설명"), userMsg(1032), doneMsg(1033, "ANSWER", "답")], false),
      ],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    type("질문");
    await send();
    await tick(1000);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);

    restore.resolve(page([pendingMsg(1029)], true, false));
    await flush();
    await tick(10_000);

    expect(api.gets().map((c) => c.path)).toEqual(["/api/chat/messages", pollPath(1032), "/api/chat/messages"]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(ids()).toEqual([1029, 1032, 1033]);
  });

  it("후속 조회가 끝난 뒤 최초 복원이 실패하면 복원을 다시 예약한다", async () => {
    const restore = deferred<unknown>();
    const api = makeFakeApi({
      get: [
        () => restore.promise,
        page([doneMsg(1033, "ANSWER", "답")], false),
        page([userMsg(1029), userMsg(1032), doneMsg(1033, "ANSWER", "답")], false),
      ],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    type("질문");
    await send();
    await tick(1000);

    restore.reject(networkError());
    await flush();
    await tick(10_000);

    expect(api.gets().map((c) => c.path)).toEqual(["/api/chat/messages", pollPath(1032), "/api/chat/messages"]);
    expect(ids()).toEqual([1029, 1032, 1033]);
  });

  it("후속 조회가 끝난 뒤 다시 예약된 복원도 실패하면 복원을 계속 재시도한다", async () => {
    const restore = deferred<unknown>();
    const api = makeFakeApi({
      get: [
        () => restore.promise,
        page([doneMsg(1033, "ANSWER", "답")], false),
        networkError(),
        page([userMsg(1029), userMsg(1032), doneMsg(1033, "ANSWER", "답")], false),
      ],
      post: [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    render(<ChatWindow deps={makeDeps(api).deps} />);
    await flush();
    type("질문");
    await send();
    await tick(1000);

    restore.reject(networkError());
    await flush();
    await tick(10_000);

    expect(api.gets().map((c) => c.path)).toEqual([
      "/api/chat/messages",
      pollPath(1032),
      "/api/chat/messages",
      "/api/chat/messages",
    ]);
    expect(ids()).toEqual([1029, 1032, 1033]);
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
  });
});
