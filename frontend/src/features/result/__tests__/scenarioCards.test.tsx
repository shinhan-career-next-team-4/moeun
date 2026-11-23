// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ScenarioCards } from "../ScenarioCards";
import type { ScenarioResult } from "../types";

const scenarios: ScenarioResult[] = [
  {
    scenario: "AVG",
    afterTaxAsset: 17_120_000,
    gapToGoal: -2_880_000,
    isAchieved: false,
    goalReachMonth: null,
    monthlyPoints: [],
  },
];

const display: Record<string, string> = {
  "AVG.afterTaxAsset": "약 1,712만원",
  "AVG.gapToGoal": "-약 288만원 미달",
};

describe("3구간 머리글 둘째 줄 (#156)", () => {
  afterEach(cleanup);

  it("종목 이름이 있으면 '기준 종목 <이름> · <산정 방식>'으로 보인다", () => {
    render(
      <ScenarioCards
        scenarios={scenarios}
        display={display}
        scenarioMethod="ROLLING_DISTRIBUTION"
        productName="코스피200 추종 ETF"
      />,
    );

    expect(
      screen.getByText(
        "기준 종목 코스피200 추종 ETF · 과거 10년 수익률로 1만 가지 경우를 계산해 세 구간 (가정 보기 참고)",
      ),
    ).toBeTruthy();
  });

  it("종목 이름이 없으면 산정 방식만 보인다", () => {
    render(<ScenarioCards scenarios={scenarios} display={display} scenarioMethod="CAGR" />);

    expect(screen.getByText("과거 데이터의 연평균 수익률로 세 구간을 나눠 계산 (가정 보기 참고)")).toBeTruthy();
    expect(screen.queryByText(/기준 종목/)).toBeNull();
    expect(screen.queryByText(/S&P500/)).toBeNull();
  });

  it("산정 방식 코드가 없으면 둘째 줄을 숨긴다", () => {
    render(<ScenarioCards scenarios={scenarios} display={display} scenarioMethod={null} productName="S&P500 추종 ETF" />);

    expect(screen.queryByText(/기준 종목/)).toBeNull();
    expect(screen.queryByText(/가정 보기 참고/)).toBeNull();
  });
});
