// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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

describe("선택할 수 있는 3구간 카드 (#298)", () => {
  afterEach(cleanup);

  const three: ScenarioResult[] = (["GOOD", "AVG", "BAD"] as const).map((scenario) => ({
    scenario,
    afterTaxAsset: 50_000_000,
    gapToGoal: 1_000_000,
    isAchieved: true,
    goalReachMonth: "2030-03",
    monthlyPoints: [],
  }));
  const displayThree: Record<string, string> = {
    "GOOD.afterTaxAsset": "약 7,219만원",
    "AVG.afterTaxAsset": "약 5,800만원",
    "BAD.afterTaxAsset": "약 4,603만원",
    "GOOD.gapToGoal": "+약 5,219만원 달성",
    "AVG.gapToGoal": "+약 3,800만원 달성",
    "BAD.gapToGoal": "+약 2,603만원 달성",
    "GOOD.goalReachMonth": "2030년 3월",
    "AVG.goalReachMonth": "2030년 7월",
    "BAD.goalReachMonth": "2031년 1월",
  };

  it("onSelect가 없으면 카드는 단추가 아니다(결과 화면 그대로)", () => {
    render(<ScenarioCards scenarios={three} display={displayThree} scenarioMethod={null} />);

    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("onSelect가 있으면 카드 세 장이 단추가 되고 고른 카드만 눌린 상태(aria-pressed)다", () => {
    render(<ScenarioCards scenarios={three} display={displayThree} scenarioMethod={null} selected="AVG" onSelect={vi.fn()} />);

    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(3);
    expect(screen.getByRole("button", { name: /좋았던 때/ }).getAttribute("aria-pressed")).toBe("false");
    expect(screen.getByRole("button", { name: /평균/ }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: /나빴던 때/ }).getAttribute("aria-pressed")).toBe("false");
  });

  it("단추가 된 카드도 서버 표시 문자열을 그대로 보인다", () => {
    render(<ScenarioCards scenarios={three} display={displayThree} scenarioMethod={null} selected="AVG" onSelect={vi.fn()} />);

    const good = screen.getByRole("button", { name: /좋았던 때/ });
    expect(good.textContent).toContain("약 7,219만원");
    expect(good.textContent).toContain("+약 5,219만원 달성");
    expect(good.textContent).toContain("2030년 3월");
  });

  it("카드를 누르면 그 시나리오 코드로 onSelect를 부른다", () => {
    const onSelect = vi.fn();
    render(<ScenarioCards scenarios={three} display={displayThree} scenarioMethod={null} selected="AVG" onSelect={onSelect} />);

    fireEvent.click(screen.getByRole("button", { name: /나빴던 때/ }));
    fireEvent.click(screen.getByRole("button", { name: /좋았던 때/ }));

    expect(onSelect).toHaveBeenNthCalledWith(1, "BAD");
    expect(onSelect).toHaveBeenNthCalledWith(2, "GOOD");
  });

  it("고른 카드는 눈에 띄는 테두리로 표시된다", () => {
    render(<ScenarioCards scenarios={three} display={displayThree} scenarioMethod={null} selected="GOOD" onSelect={vi.fn()} />);

    expect(screen.getByRole("button", { name: /좋았던 때/ }).className).toContain("border-primary");
    expect(screen.getByRole("button", { name: /평균/ }).className).not.toContain("border-primary");
  });
});

describe("안내 문구 줄의 오른쪽 자리 noticeSlot (#298)", () => {
  afterEach(cleanup);

  const one: ScenarioResult[] = [
    { scenario: "AVG", afterTaxAsset: 1, gapToGoal: 1, isAchieved: true, goalReachMonth: "2030-03", monthlyPoints: [] },
  ];
  const notice = "세 가지 경우는 과거 데이터의 범위를 나눈 시나리오이고 미래를 예측한 값이 아니에요";

  it("noticeSlot이 없으면 안내 문구만 있다(결과 화면 그대로)", () => {
    render(<ScenarioCards scenarios={one} display={{}} scenarioMethod={null} />);

    expect(screen.getByText(notice)).toBeTruthy();
    expect(screen.getByText(notice).parentElement?.children).toHaveLength(1);
  });

  it("noticeSlot을 주면 안내 문구와 같은 줄(같은 부모)에 보인다", () => {
    render(
      <ScenarioCards scenarios={one} display={{}} scenarioMethod={null} noticeSlot={<button type="button">슬롯</button>} />,
    );

    const row = screen.getByText(notice).parentElement as HTMLElement;
    expect(row.contains(screen.getByRole("button", { name: "슬롯" }))).toBe(true);
  });
});
