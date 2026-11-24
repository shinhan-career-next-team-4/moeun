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
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("길이를 비교하도록 새 길이(420px) 그래프 아래에 기존 길이(300px) 그래프가 하나 더 있다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    const tall = screen.getByRole("group", { name: "예상 자산 누적 그래프" });
    const old = screen.getByRole("group", { name: "예상 자산 누적 그래프(기존 길이)" });
    expect(tall.querySelector("svg")?.getAttribute("viewBox")).toBe("0 0 640 420");
    expect(old.querySelector("svg")?.getAttribute("viewBox")).toBe("0 0 640 300");
    expect(screen.getByText("새 길이 (세로 420px)")).toBeTruthy();
    expect(screen.getByText("기존 길이 (세로 300px)")).toBeTruthy();
    expect(tall.compareDocumentPosition(old) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("두 그래프는 같은 카드·월별/연별 선택을 따른다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));
    for (const name of ["예상 자산 누적 그래프", "예상 자산 누적 그래프(기존 길이)"]) {
      const graph = screen.getByRole("group", { name });
      fireEvent.keyDown(graph, { key: "End" });
      fireEvent.keyDown(graph, { key: "ArrowLeft" });
      expect(within(screen.getByRole("tooltip")).getByText("9년")).toBeTruthy();
      fireEvent.keyDown(graph, { key: "Escape" });
    }

    fireEvent.click(screen.getByRole("button", { name: /좋았던 때/ }));
    const legends = screen.getAllByRole("list", { name: "범례" });
    expect(legends).toHaveLength(2);
    for (const legend of legends) {
      expect(within(legend).getByText(`ETF 수익 ${formatMoneyShort(MOCK_PROJECTION.GOOD[119].etfGain)}`)).toBeTruthy();
    }
  });

  it("'10년 뒤 예상 자산' 결론 숫자 블록은 없다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByText("10년 뒤 예상 자산")).toBeNull();
    expect(screen.queryByRole("region", { name: "10년 뒤 예상 자산" })).toBeNull();
  });

  it("월별/연별 토글은 3구간 카드 아래 안내 문구와 같은 줄에 있다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    const notice = screen.getByText("세 가지 경우는 과거 데이터의 범위를 나눈 시나리오이고 미래를 예측한 값이 아니에요");
    const row = notice.parentElement as HTMLElement;
    expect(row.contains(screen.getByRole("radiogroup", { name: "그래프 단위" }))).toBe(true);
    // 그래프 카드 안에는 토글이 더 없다
    expect(screen.getAllByRole("radiogroup", { name: "그래프 단위" })).toHaveLength(1);
  });

  it("연별로 바꾸면 그래프의 점이 12개월 간격이 된다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));
    const graph = screen.getByRole("group", { name: "예상 자산 누적 그래프" });
    fireEvent.keyDown(graph, { key: "End" });
    fireEvent.keyDown(graph, { key: "ArrowLeft" });

    expect(within(screen.getByRole("tooltip")).getByText("9년")).toBeTruthy();
  });

  it("위쪽 3구간 카드가 시나리오 버튼이다: 처음엔 평균이 눌려 있고 카드를 누르면 그래프 범례가 바뀐다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.getByRole("button", { name: /평균/ }).getAttribute("aria-pressed")).toBe("true");
    const legend = screen.getAllByRole("list", { name: "범례" })[0];
    expect(within(legend).getByText(`ETF 수익 ${formatMoneyShort(MOCK_PROJECTION.AVG[119].etfGain)}`)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /좋았던 때/ }));

    expect(screen.getByRole("button", { name: /좋았던 때/ }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: /평균/ }).getAttribute("aria-pressed")).toBe("false");
    expect(within(legend).getByText(`ETF 수익 ${formatMoneyShort(MOCK_PROJECTION.GOOD[119].etfGain)}`)).toBeTruthy();
  });

  it("그래프 안의 '하위 10%·상위 10%' 시나리오 버튼은 없다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByText("하위 10%")).toBeNull();
    expect(screen.queryByText("상위 10%")).toBeNull();
  });

  it("기간을 고르는 '데모 기간' 버튼은 없고 기간 선택 없이 그려진다", () => {
    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByRole("radiogroup", { name: "데모 기간" })).toBeNull();
    expect(screen.queryByRole("radio", { name: "30년" })).toBeNull();
  });

  it("개발 환경이 아니면(운영 빌드) 데모 경로는 열리지 않는다", () => {
    vi.stubEnv("DEV", false);
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    renderAt(PROJECTION_DEMO_PATH);

    expect(screen.queryByRole("heading", { name: "예상 자산 그래프 데모" })).toBeNull();
  });
});
