// @vitest-environment jsdom
// Issue #52 리뷰 회귀(AC-7): 늦게 도착한 최초 복원이, 모순을 발견한 뒤에 필요해진 안전장치 복원을 대신하지 못한다.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatWindow } from "../index";
import { doneMsg, flush, makeDeps, page, pendingMsg, tick, userMsg } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const RESTORE = "/api/chat/messages";

describe("응답이 늦은 최초 복원", () => {
  it("그 사이 증분 조회가 모순(hasPending만 true)을 보여도 과거 PENDING(1029)이 완료 상태로 복원된다", async () => {
    let releaseFirstRestore: (value: unknown) => void = () => {};
    const firstRestore = new Promise((resolve) => {
      releaseFirstRestore = resolve;
    });
    const gets: string[] = [];
    let restoreCalls = 0;
    const request = vi.fn(async (method: string, path: string) => {
      if (method === "POST") return { userMessageId: 1032, assistantMessageId: 1033 };
      gets.push(path);
      if (path === RESTORE) {
        restoreCalls += 1;
        // 첫 복원은 1029가 아직 처리 중이던 시점의 스냅샷을 늦게 돌려준다.
        if (restoreCalls === 1) return firstRestore;
        return page([doneMsg(1029, "EXPLANATION", "설명"), userMsg(1032), doneMsg(1033, "ANSWER", "답")], false);
      }
      const afterId = Number(new URL(path, "http://x").searchParams.get("afterId"));
      // 1029가 아직 처리 중이라 서버는 hasPending=true지만, 이 호출이 돌려주는 메시지에는 PENDING이 없다.
      if (afterId === 1032) return page([doneMsg(1033, "ANSWER", "답")], true, false);
      return page([], false);
    });
    render(<ChatWindow deps={makeDeps({ request }).deps} />);
    await flush(); // 최초 복원이 응답을 기다린다

    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await flush();
    await tick(1000); // afterId=1032: hasPending=true인데 가진 PENDING 없음
    await tick(1000); // 다음 호출
    await tick(1000);
    releaseFirstRestore(page([pendingMsg(1029)], true, false));
    await flush();
    await tick(10_000);

    expect(gets.filter((p) => p === RESTORE).length).toBeGreaterThanOrEqual(2); // 모순을 푸는 복원이 나갔다
    const ids = screen.queryAllByTestId("chat-message").map((el) => Number(el.getAttribute("data-message-id")));
    expect(ids).toEqual([1029, 1032, 1033]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);

    const settled = request.mock.calls.length;
    await tick(30_000);
    expect(request.mock.calls).toHaveLength(settled);
  });

  it("안전장치 복원이 성공해 끝난 뒤 늦은 최초 복원이 실패해도 폴링을 되살리지 않는다", async () => {
    let rejectFirstRestore: (reason: unknown) => void = () => {};
    const firstRestore = new Promise((_, reject) => {
      rejectFirstRestore = reject;
    });
    let restoreCalls = 0;
    const request = vi.fn(async (method: string, path: string) => {
      if (method === "POST") return { userMessageId: 1032, assistantMessageId: 1033 };
      if (path === RESTORE) {
        restoreCalls += 1;
        if (restoreCalls === 1) return firstRestore;
        return page([doneMsg(1029, "EXPLANATION", "설명"), userMsg(1032), doneMsg(1033, "ANSWER", "답")], false, false);
      }
      return page([doneMsg(1033, "ANSWER", "답")], true, false);
    });
    render(<ChatWindow deps={makeDeps({ request }).deps} />);
    await flush();

    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await flush();
    await tick(1000); // afterId=1032: hasPending=true인데 가진 PENDING 없음
    await tick(1000); // 안전장치 전체 복원이 성공하고 폴링이 끝난다
    const settled = request.mock.calls.length;

    rejectFirstRestore(new Error("network"));
    await flush();
    await tick(30_000);

    expect(request.mock.calls).toHaveLength(settled);
    const ids = screen.queryAllByTestId("chat-message").map((el) => Number(el.getAttribute("data-message-id")));
    expect(ids).toEqual([1029, 1032, 1033]);
    expect(screen.queryAllByTestId("chat-polling-failed")).toHaveLength(0);
  });
});
