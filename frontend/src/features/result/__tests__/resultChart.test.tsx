// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { ResultChart } from "../ResultChart";
import type { PrincipalPoint, ScenarioResult } from "../types";

const baseDisplay: Record<string, string> = {
  goalAmount: "약 2,000만원",
  calcStartMonth: "2026년 11월",
  targetMonth: "2027년 02월",
  "AVG.afterTaxAsset": "약 1,200만원",
};

const sampleScenarios: ScenarioResult[] = [
  {
    scenario: "GOOD",
    afterTaxAsset: 14000000,
    gapToGoal: -6000000,
    isAchieved: false,
    goalReachMonth: null,
    monthlyPoints: [
      { month: "2026-11", afterTaxAsset: 1000000 },
      { month: "2026-12", afterTaxAsset: 2200000 },
      { month: "2027-01", afterTaxAsset: 3600000 },
    ],
  },
  {
    scenario: "AVG",
    afterTaxAsset: 12000000,
    gapToGoal: -8000000,
    isAchieved: false,
    goalReachMonth: null,
    monthlyPoints: [
      { month: "2026-11", afterTaxAsset: 1000000 },
      { month: "2026-12", afterTaxAsset: 2000000 },
      { month: "2027-01", afterTaxAsset: 3100000 },
    ],
  },
  {
    scenario: "BAD",
    afterTaxAsset: 10500000,
    gapToGoal: -9500000,
    isAchieved: false,
    goalReachMonth: null,
    monthlyPoints: [
      { month: "2026-11", afterTaxAsset: 1000000 },
      { month: "2026-12", afterTaxAsset: 1800000 },
      { month: "2027-01", afterTaxAsset: 2600000 },
    ],
  },
];

// 누적 납입 원금(보유 자산 + 월 저축액 × 경과 개월). 서버가 result.principalPoints로 내려 준다
const samplePrincipal: PrincipalPoint[] = [
  { month: "2026-11", principal: 1000000 },
  { month: "2026-12", principal: 1900000 },
  { month: "2027-01", principal: 2800000 },
];

describe("ResultChart", () => {
  afterEach(() => {
    cleanup();
  });

  it("GOOD(빨강), AVG(초록), BAD(파랑) 3개 선과 누적 납입 원금 막대를 함께 그린다", () => {
    render(
      <ResultChart
        scenarios={sampleScenarios}
        goalAmount={20000000}
        display={baseDisplay}
        principalPoints={samplePrincipal}
      />,
    );

    const chart = screen.getByLabelText("예상 자산 추이 그래프");
    expect(within(chart).getByText("누적 납입 원금")).toBeTruthy();
    expect(chart.querySelector('path[data-scenario="GOOD"]')?.getAttribute("stroke")).toBe("#dc2626");
    expect(chart.querySelector('path[data-scenario="AVG"]')?.getAttribute("stroke")).toBe("#16a34a");
    expect(chart.querySelector('path[data-scenario="BAD"]')?.getAttribute("stroke")).toBe("#2563eb");

    const bars = chart.querySelectorAll('line[data-series="PRINCIPAL"][stroke="#000000"]');
    expect(bars.length).toBe(3);
  });

  it("달별 히트 영역을 클릭하면 onSelectMonth(month, index)를 호출하고 선택된 월 포인트를 강조한다", () => {
    const onSelectMonth = vi.fn();
    render(
      <ResultChart
        scenarios={sampleScenarios}
        goalAmount={20000000}
        display={baseDisplay}
        principalPoints={samplePrincipal}
        selectedMonth="2026-12"
        onSelectMonth={onSelectMonth}
      />,
    );

    const chart = screen.getByLabelText("예상 자산 추이 그래프");
    // 선택된 월(2026-12)의 3개 시나리오 강조 포인트
    expect(chart.querySelectorAll("circle").length).toBe(3);

    fireEvent.click(screen.getByRole("button", { name: "2027-01 선택" }));
    expect(onSelectMonth).toHaveBeenCalledWith("2027-01", 2);
  });

  it("누적 납입 원금 막대의 높이는 서버가 준 principal 값에서 온다(프론트가 더하지 않는다)", () => {
    render(
      <ResultChart
        scenarios={sampleScenarios}
        goalAmount={20000000}
        display={baseDisplay}
        principalPoints={samplePrincipal}
      />,
    );

    const bars = [...screen.getByLabelText("예상 자산 추이 그래프").querySelectorAll('line[data-series="PRINCIPAL"]')];
    const heights = bars.map((bar) => Number(bar.getAttribute("y1")) - Number(bar.getAttribute("y2")));
    // 값이 커질수록 막대가 길다(1,000,000 < 1,900,000 < 2,800,000)
    expect(heights[0]).toBeLessThan(heights[1]);
    expect(heights[1]).toBeLessThan(heights[2]);
  });

  it("principalPoints가 없으면 막대도 범례도 없다", () => {
    render(<ResultChart scenarios={sampleScenarios} goalAmount={20000000} display={baseDisplay} />);

    const chart = screen.getByLabelText("예상 자산 추이 그래프");
    expect(within(chart).queryByText("누적 납입 원금")).toBeNull();
    expect(chart.querySelectorAll('line[data-series="PRINCIPAL"]').length).toBe(0);
  });

  it("범위 밴드(닫힌 path)는 그리지 않는다", () => {
    render(
      <ResultChart scenarios={sampleScenarios} goalAmount={20000000} display={baseDisplay} principalPoints={samplePrincipal} />,
    );

    const closed = [...screen.getByLabelText("예상 자산 추이 그래프").querySelectorAll("path")].filter((node) =>
      node.getAttribute("d")?.endsWith("Z"),
    );
    expect(closed.length).toBe(0);
  });

  it("ETF 0%(AVG 하나)여도 평균 선과 누적 납입 원금 막대를 그린다", () => {
    render(
      <ResultChart
        scenarios={[sampleScenarios[1]]}
        goalAmount={20000000}
        display={baseDisplay}
        principalPoints={samplePrincipal}
      />,
    );

    const chart = screen.getByLabelText("예상 자산 추이 그래프");
    expect(chart.querySelector('path[data-scenario="AVG"]')).toBeTruthy();
    expect(chart.querySelector('path[data-scenario="GOOD"]')).toBeNull();
    expect(chart.querySelectorAll('line[data-series="PRINCIPAL"]').length).toBe(3);
  });

  it("월 영역에서 Enter·Space를 눌러도 onSelectMonth를 부른다(키보드)", () => {
    const onSelectMonth = vi.fn();
    render(
      <ResultChart
        scenarios={sampleScenarios}
        goalAmount={20000000}
        display={baseDisplay}
        onSelectMonth={onSelectMonth}
      />,
    );

    fireEvent.keyDown(screen.getByRole("button", { name: "2026-12 선택" }), { key: "Enter" });
    fireEvent.keyDown(screen.getByRole("button", { name: "2027-01 선택" }), { key: " " });

    expect(onSelectMonth).toHaveBeenNthCalledWith(1, "2026-12", 1);
    expect(onSelectMonth).toHaveBeenNthCalledWith(2, "2027-01", 2);
  });

  it("onSelectMonth가 없으면 월 영역은 단추가 아니다", () => {
    render(<ResultChart scenarios={sampleScenarios} goalAmount={20000000} display={baseDisplay} />);

    expect(screen.queryByRole("button", { name: "2026-12 선택" })).toBeNull();
  });
});
