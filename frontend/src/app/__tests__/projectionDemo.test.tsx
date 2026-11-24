// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { PROJECTION_DEMO_PATH } from "../routes";

// 예상 자산 누적 그래프 데모 페이지(#298). mock 데이터로 그리는 확인용이라 개발 환경에서만 열린다.

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );

describe("예상 자산 그래프 데모 라우트", () => {
  it("경로 상수는 /demo/projection이다", () => {
    expect(PROJECTION_DEMO_PATH).toBe("/demo/projection");
  });

  it("개발 환경에서는 로그인·API 없이 mock 그래프가 바로 보인다", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.getByRole("heading", { name: "예상 자산 그래프 데모" })).toBeTruthy();
    expect(screen.getByText("mock 데이터로 그린 화면입니다. 서버 연동 전 확인용이에요.")).toBeTruthy();
    expect(screen.getByRole("group", { name: "예상 자산 누적 그래프" })).toBeTruthy();
    expect(screen.getByText("10년 뒤 예상 자산")).toBeTruthy();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("개발 환경이 아니면(운영 빌드) 데모 경로는 열리지 않는다", () => {
    vi.stubEnv("DEV", false);
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByRole("heading", { name: "예상 자산 그래프 데모" })).toBeNull();
  });
});
