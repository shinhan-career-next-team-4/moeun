// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { formatMoneyShort } from "../../features/projection/formatMoney";
import { MOCK_PROJECTION } from "../../features/projection/mockProjection";
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

  it("위쪽 3구간 카드가 시나리오 버튼이다: 처음엔 평균이 눌려 있고 카드를 누르면 그래프 숫자가 바뀐다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.getByRole("button", { name: /평균/ }).getAttribute("aria-pressed")).toBe("true");
    const headline = screen.getByRole("region", { name: "10년 뒤 예상 자산" });
    expect(within(headline).getByText(`${formatMoneyShort(MOCK_PROJECTION.AVG[119].total)}원`)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /좋았던 때/ }));

    expect(screen.getByRole("button", { name: /좋았던 때/ }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: /평균/ }).getAttribute("aria-pressed")).toBe("false");
    expect(within(headline).getByText(`${formatMoneyShort(MOCK_PROJECTION.GOOD[119].total)}원`)).toBeTruthy();
  });

  it("그래프 안의 '하위 10%·상위 10%' 시나리오 버튼은 없다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByText("하위 10%")).toBeNull();
    expect(screen.queryByText("상위 10%")).toBeNull();
  });

  it("기간을 고르는 '데모 기간' 버튼은 없고 10년치가 그려진다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByRole("radiogroup", { name: "데모 기간" })).toBeNull();
    expect(screen.queryByRole("radio", { name: "30년" })).toBeNull();
    expect(screen.getByRole("region", { name: "10년 뒤 예상 자산" })).toBeTruthy();
  });

  it("개발 환경이 아니면(운영 빌드) 데모 경로는 열리지 않는다", () => {
    vi.stubEnv("DEV", false);
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByRole("heading", { name: "예상 자산 그래프 데모" })).toBeNull();
  });
});
