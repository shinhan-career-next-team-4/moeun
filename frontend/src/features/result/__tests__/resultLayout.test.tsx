// @vitest-environment jsdom
// Issue #224: 결과 화면 틀 — 데스크톱은 패널이 열려 있고, 좁은 화면은 닫혀 있다가 단추로 연다.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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
  it("패널이 열려 있고 360px 고정 폭 클래스를 쓴다. 본문·조건 바도 보인다", () => {
    stubViewport(true);
    renderLayout();
    expect(panel().className).toContain("lg:w-[360px]");
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
