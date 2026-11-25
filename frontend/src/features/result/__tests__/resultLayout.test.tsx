// @vitest-environment jsdom
// Issue #224: 결과 화면 틀 — 데스크톱은 패널이 열려 있고, 좁은 화면은 닫혀 있다가 단추로 연다.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { act } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResultLayout } from "../ResultLayout";

function stubViewport(desktop: boolean) {
  vi.stubGlobal("matchMedia", (query: string) => ({ matches: desktop, media: query, addEventListener() {}, removeEventListener() {} }));
}

function stubChatFetch() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ messages: [], hasPending: false, answerPending: false }), { headers: { "Content-Type": "application/json" } })),
  );
}

afterEach(() => {
  window.localStorage.removeItem("moeun.chatPanel");
  cleanup();
  vi.unstubAllGlobals();
});

function renderLayout() {
  stubChatFetch();
  return render(
    <ResultLayout bar={<div>조건 바</div>}>
      <p>본문</p>
    </ResultLayout>,
  );
}

const panel = () => screen.getByTestId("result-panel");

describe("데스크톱(1024px 이상)", () => {
  it("패널이 열려 있고 기본 폭 360px를 쓴다. 본문·조건 바도 보인다", () => {
    stubViewport(true);
    renderLayout();
    expect(panel().style.getPropertyValue("--chat-panel-width")).toBe("360px");
    expect(panel().className).not.toContain("hidden");
    expect(screen.getByText("본문")).toBeTruthy();
    expect(screen.getByText("조건 바")).toBeTruthy();
    expect(screen.queryByTestId("result-panel-open")).toBeNull();
  });

  it("접기 단추로 닫으면 'AI 설명' 단추가 나타나고, 대화창은 계속 떠 있다", () => {
    stubViewport(true);
    renderLayout();
    fireEvent.click(screen.getByTestId("result-panel-close"));
    expect(panel().className).toContain("hidden");
    expect(screen.getByTestId("chat-window")).toBeTruthy();
    fireEvent.click(screen.getByTestId("result-panel-open"));
    expect(panel().className).not.toContain("hidden");
  });
});

describe("좁은 화면(1024px 미만)", () => {
  it("패널이 닫혀 있고 'AI 설명' 단추로 연다. 열면 서랍(768px 미만은 전체 화면) 클래스를 쓴다", () => {
    stubViewport(false);
    renderLayout();
    expect(panel().className).toContain("hidden");
    fireEvent.click(screen.getByRole("button", { name: "AI 설명" }));
    expect(panel().className).toContain("fixed");
    expect(panel().className).toContain("w-full");
    expect(panel().className).toContain("md:w-[400px]");
  });

  it("뒷배경을 누르면 서랍이 닫힌다", () => {
    stubViewport(false);
    renderLayout();
    fireEvent.click(screen.getByTestId("result-panel-open"));
    fireEvent.click(screen.getByTestId("result-panel-backdrop"));
    expect(panel().className).toContain("hidden");
  });
});

describe("키보드와 포커스", () => {
  it("열면 포커스가 패널로 가고, Esc로 닫으면 'AI 설명' 단추로 돌아온다", () => {
    stubViewport(false);
    renderLayout();
    fireEvent.click(screen.getByTestId("result-panel-open"));
    expect(document.activeElement).toBe(panel());

    fireEvent.keyDown(panel(), { key: "Escape" });
    expect(panel().className).toContain("hidden");
    expect(document.activeElement).toBe(screen.getByTestId("result-panel-open"));
  });

  it("처음 그릴 때는 포커스를 가져가지 않는다", () => {
    stubViewport(true);
    renderLayout();
    expect(document.activeElement).toBe(document.body);
  });
});

describe("새 설명 점 표시", () => {
  const pendingPage = {
    messages: [{ id: 2, role: "ASSISTANT", messageType: null, status: "PENDING", content: null, citations: [], createdAt: "2026-11-23T10:00:00+09:00" }],
    hasPending: true,
    answerPending: false,
  };
  const donePage = {
    messages: [{ id: 2, role: "ASSISTANT", messageType: "EXPLANATION", status: "COMPLETED", content: "설명이에요", citations: [], createdAt: "2026-11-23T10:00:00+09:00" }],
    hasPending: false,
    answerPending: false,
  };

  it("닫힌 동안 대기 중이던 설명이 끝나면 점이 뜨고, 열면 사라진다", async () => {
    vi.useFakeTimers();
    stubViewport(false);
    const pages = [pendingPage, donePage];
    let call = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify(pages[Math.min(call++, pages.length - 1)]), { headers: { "Content-Type": "application/json" } })),
    );
    render(
      <ResultLayout bar={<div>조건 바</div>}>
        <p>본문</p>
      </ResultLayout>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.queryByTestId("result-panel-unread")).toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500);
    });
    expect(screen.getByTestId("result-panel-unread").getAttribute("aria-label")).toBe("새 설명이 도착했어요");

    fireEvent.click(screen.getByTestId("result-panel-open"));
    expect(screen.queryByTestId("result-panel-unread")).toBeNull();
    vi.useRealTimers();
  });
});

describe("폭 조절과 좌·우 위치(#362)", () => {
  const handle = () => screen.getByTestId("result-panel-resize");

  it("키보드로 폭을 키우고 줄이며 280~720px 안에 머물고 기억한다", () => {
    stubViewport(true);
    vi.stubGlobal("innerWidth", 2000);
    renderLayout();

    fireEvent.keyDown(handle(), { key: "ArrowLeft" });
    expect(panel().style.getPropertyValue("--chat-panel-width")).toBe("376px");
    expect(JSON.parse(window.localStorage.getItem("moeun.chatPanel") ?? "{}").width).toBe(376);

    for (let i = 0; i < 40; i += 1) fireEvent.keyDown(handle(), { key: "ArrowLeft" });
    expect(panel().style.getPropertyValue("--chat-panel-width")).toBe("720px");
    for (let i = 0; i < 80; i += 1) fireEvent.keyDown(handle(), { key: "ArrowRight" });
    expect(panel().style.getPropertyValue("--chat-panel-width")).toBe("280px");
  });

  it("제목 옆 단추로 왼쪽·오른쪽을 바꾸고, 바뀐 위치와 폭을 다음 방문에도 쓴다", () => {
    stubViewport(true);
    renderLayout();
    expect(panel().className).not.toContain("lg:border-r");

    fireEvent.click(screen.getByRole("button", { name: "대화창을 왼쪽으로 옮기기" }));
    expect(panel().className).toContain("lg:border-r");
    expect(screen.getByRole("button", { name: "대화창을 오른쪽으로 옮기기" })).toBeTruthy();
    expect(JSON.parse(window.localStorage.getItem("moeun.chatPanel") ?? "{}").side).toBe("left");

    cleanup();
    renderLayout();
    expect(panel().className).toContain("lg:border-r");
  });

  it("왼쪽 패널에서는 방향키가 반대로 움직인다(오른쪽 끝이 안쪽 가장자리)", () => {
    stubViewport(true);
    vi.stubGlobal("innerWidth", 2000);
    renderLayout();
    fireEvent.click(screen.getByRole("button", { name: "대화창을 왼쪽으로 옮기기" }));

    fireEvent.keyDown(handle(), { key: "ArrowRight" });
    expect(panel().style.getPropertyValue("--chat-panel-width")).toBe("376px");
  });

  it("저장된 값이 깨져 있으면 기본값(오른쪽, 360px)으로 연다", () => {
    stubViewport(true);
    window.localStorage.setItem("moeun.chatPanel", "{깨짐");
    renderLayout();

    expect(panel().style.getPropertyValue("--chat-panel-width")).toBe("360px");
    expect(panel().className).not.toContain("lg:border-r");
  });
});
