// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AssumptionsSection } from "../AssumptionsSection";
import type { Assumptions } from "../types";

const assumptions: Assumptions = {
  savingsRatePct: 3.12,
  isDefaultSavingsRate: false,
  savingsRateBaseMonth: "2026-10",
  savingsRatioPct: 50,
  taxRatePct: 15.4,
  scenarioMethod: "ROLLING_DISTRIBUTION",
  sp500BaseDate: "2026-10",
  excludedItems: [],
};

function renderAssumptions(display: Record<string, string>) {
  render(<AssumptionsSection assumptions={assumptions} display={display} />);
  return screen.getByLabelText("계산 가정");
}

describe("가정 보기의 ETF 종목 표시 (#156 1단계)", () => {
  afterEach(cleanup);

  it("종목 이름과 매매차익 세율을 display 문자열 그대로 보여 준다", () => {
    const section = renderAssumptions({
      "assumptions.etfProductName": "코스피200 추종 ETF",
      "assumptions.etfGainTaxRatePct": "0%(국내 주식형 ETF 매매차익 비과세)",
    });

    expect(section.textContent).toContain("ETF 종목");
    expect(section.textContent).toContain("코스피200 추종 ETF");
    expect(section.textContent).toContain("ETF 매매차익 세율");
    expect(section.textContent).toContain("0%(국내 주식형 ETF 매매차익 비과세)");
  });

  it("ETF 0%라 종목 display 키가 없으면 종목·세율 줄을 생략한다", () => {
    const section = renderAssumptions({ "assumptions.taxRatePct": "15.4%" });

    expect(section.textContent).not.toContain("ETF 종목");
    expect(section.textContent).not.toContain("ETF 매매차익 세율");
  });

  it("기준일 라벨은 종목과 무관한 'ETF 기준일'이다", () => {
    const section = renderAssumptions({ "assumptions.sp500BaseDate": "2026년 10월" });

    expect(section.textContent).toContain("ETF 기준일");
    expect(section.textContent).not.toContain("S&P500 기준일");
  });
});
