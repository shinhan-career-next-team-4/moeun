// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MonthDetailPanel } from "../MonthDetailPanel";
import type { MonthlyDetail } from "../types";

// 그래프에서 월을 눌렀을 때의 상세(#292). 값은 모두 서버가 서식한 문자열이다(NFR-INT-01).

const detail: MonthlyDetail = {
  month: "2026-12",
  label: "2026년 12월",
  principal: "약 1,000만원",
  afterTaxAsset: { GOOD: "약 1,100만원", AVG: "약 1,050만원", BAD: "약 1,010만원" },
};

function panel() {
  return screen.getByRole("region", { name: "월별 상세" });
}

describe("월별 상세 패널 (#292)", () => {
  afterEach(cleanup);

  it("월 라벨과 시나리오별 세후 자산, 누적 납입 원금을 서버 문자열 그대로 보인다", () => {
    render(<MonthDetailPanel detail={detail} onClose={vi.fn()} />);

    const view = within(panel());
    expect(view.getByText("2026년 12월")).toBeTruthy();
    expect(view.getByText("약 1,100만원")).toBeTruthy();
    expect(view.getByText("약 1,050만원")).toBeTruthy();
    expect(view.getByText("약 1,010만원")).toBeTruthy();
    expect(view.getByText("약 1,000만원")).toBeTruthy();
    expect(view.getByText("누적 납입 원금")).toBeTruthy();
  });

  it("시나리오는 좋았던 때·평균·나빴던 때 순서로 보인다", () => {
    render(<MonthDetailPanel detail={detail} onClose={vi.fn()} />);

    const labels = within(panel())
      .getAllByRole("term")
      .map((term) => term.textContent);
    expect(labels).toEqual(["좋았던 때", "평균", "나빴던 때", "누적 납입 원금"]);
  });

  it("응답에 있는 시나리오만 보인다(ETF 0%는 평균 하나)", () => {
    render(
      <MonthDetailPanel detail={{ ...detail, afterTaxAsset: { AVG: "약 1,050만원" } }} onClose={vi.fn()} />,
    );

    const labels = within(panel())
      .getAllByRole("term")
      .map((term) => term.textContent);
    expect(labels).toEqual(["평균", "누적 납입 원금"]);
    expect(within(panel()).queryByText("좋았던 때")).toBeNull();
  });

  it("닫기를 누르면 onClose를 부른다", () => {
    const onClose = vi.fn();
    render(<MonthDetailPanel detail={detail} onClose={onClose} />);

    fireEvent.click(screen.getByRole("button", { name: "닫기" }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
