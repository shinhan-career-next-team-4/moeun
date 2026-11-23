// @vitest-environment jsdom
// Issue #224: 대화 패널 — 머리글, 설명 실패 안내와 다시 시도, 새 답변 알림, 자동 하단 스크롤.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatWindow } from "../index";
import { doneMsg, failedMsg, flush, makeDeps, makeFakeApi, page, pendingMsg, tick, userMsg } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

async function mount(replies: Parameters<typeof makeFakeApi>[0], props: Partial<Parameters<typeof ChatWindow>[0]> = {}) {
  const api = makeFakeApi(replies);
  const { deps } = makeDeps(api);
  render(<ChatWindow deps={deps} {...props} />);
  await flush();
  return api;
}

describe("머리글과 안내", () => {
  it("제목과 '숫자는 계산 엔진이 만든다'는 안내가 보인다", async () => {
    await mount({ get: [page([], false)] });
    expect(screen.getByRole("heading", { name: "AI 설명 · 대화" })).toBeTruthy();
    expect(screen.getByText(/숫자는 계산 엔진이 만들고/)).toBeTruthy();
  });

  it("headerAction으로 받은 단추가 머리글에 붙는다", async () => {
    await mount({ get: [page([], false)] }, { headerAction: <button type="button">접기</button> });
    expect(screen.getByRole("button", { name: "접기" })).toBeTruthy();
  });
});

describe("빈 상태", () => {
  it("안내 문구와 예시 버튼이 세 개 보인다", async () => {
    await mount({ get: [page([], false)] });
    expect(screen.getByTestId("chat-empty").textContent).toContain("바꿔 보고 싶은 조건");
    expect(screen.getAllByTestId("chat-suggestion")).toHaveLength(3);
  });

  it("사용자가 질문한 뒤에는 예시를 숨긴다", async () => {
    await mount({ get: [page([userMsg(1030), doneMsg(1031, "ANSWER", "답이에요")], false)] });
    expect(screen.queryByTestId("chat-empty")).toBeNull();
  });
});

describe("로딩", () => {
  it("응답 대기 중에는 타이핑 표시가 보이고 전송 단추가 막힌다", async () => {
    await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true, true)] });
    expect(screen.getByRole("status", { name: "답변을 쓰는 중이에요" })).toBeTruthy();
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("설명 실패", () => {
  it("실패한 설명 아래에 [다시 시도]가 있다(plan이 CALCULATED일 때)", async () => {
    // 공유 plan store가 비어 있으면 settledStatus가 null이라 단추가 없다. 있는 경우는 retryExplanation 테스트가 덮는다.
    await mount({ get: [page([failedMsg(1032)], false)] });
    expect(screen.queryByTestId("chat-retry-failure")).toBeNull();
    expect(screen.getByTestId("chat-message").getAttribute("data-status")).toBe("FAILED");
  });
});

describe("새 답변 알림", () => {
  it("완료된 어시스턴트 메시지가 늘 때만 onAssistantMessage를 부른다", async () => {
    const onAssistantMessage = vi.fn();
    await mount(
      { get: [page([userMsg(1030), pendingMsg(1031)], true, true), page([userMsg(1030), doneMsg(1031, "ANSWER", "답")], false)] },
      { onAssistantMessage },
    );
    expect(onAssistantMessage).not.toHaveBeenCalled();
    await tick(1000);
    expect(onAssistantMessage).toHaveBeenCalledTimes(1);
  });
});

describe("자동 하단 스크롤", () => {
  function setScrollGeometry(box: HTMLElement, { scrollTop, scrollHeight, clientHeight }: Record<string, number>) {
    Object.defineProperty(box, "scrollHeight", { configurable: true, value: scrollHeight });
    Object.defineProperty(box, "clientHeight", { configurable: true, value: clientHeight });
    box.scrollTop = scrollTop;
  }

  it("맨 아래에 있으면 새 메시지에 따라 내려가고, 위로 올려 읽는 중이면 그대로다", async () => {
    await mount({
      get: [page([userMsg(1030), pendingMsg(1031)], true, true), page([userMsg(1030), doneMsg(1031, "ANSWER", "답")], true, true), page([userMsg(1030), doneMsg(1031, "ANSWER", "답"), doneMsg(1032, "ANSWER", "또")], false)],
    });
    const box = screen.getByTestId("chat-messages");

    // 위로 올려 읽는 중: 맨 아래에서 200px 위
    setScrollGeometry(box, { scrollTop: 100, scrollHeight: 500, clientHeight: 200 });
    fireEvent.scroll(box);
    await tick(1000);
    expect(box.scrollTop).toBe(100);

    // 다시 맨 아래로 내려오면 새 메시지에 따라 내려간다
    setScrollGeometry(box, { scrollTop: 300, scrollHeight: 500, clientHeight: 200 });
    fireEvent.scroll(box);
    setScrollGeometry(box, { scrollTop: 300, scrollHeight: 700, clientHeight: 200 });
    await tick(1000);
    expect(box.scrollTop).toBe(700);
  });
});
