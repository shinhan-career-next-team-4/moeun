import type { ScenarioResult } from "../result/types";
import type { ProjectionData, ProjectionPoint, ProjectionScenario } from "./types";

/**
 * 연동 전 화면 확인용 mock 데이터(#298). 실제 값은 서버(계산 엔진)가 계산해 내려 준다.
 *
 * 월 50만원을 적금 50% : ETF 50%로 모으는 가상의 계획이다. 이 파일은 **mock이라서** 값을 직접 만든다.
 * 화면 컴포넌트는 이런 계산을 하지 않고, 받은 값을 그대로 그린다.
 */

const MONTHLY_SAVING = 500_000;
const SAVINGS_SHARE = 0.5;
const SAVINGS_ANNUAL_RATE = 0.032;
const WON_PER_MAN = 10_000;
/** 목표 금액 = 마지막 달 총 납입 원금의 120%(시나리오마다 달성 여부가 갈리게). */
const GOAL_RATE = 1.2;
/** 계산 시작월(결과 화면 목과 같은 2026-11). */
const START_YEAR = 2026;
const START_MONTH_INDEX = 10;

const SCENARIO_ORDER: readonly ProjectionScenario[] = ["GOOD", "AVG", "BAD"];

/** 시나리오별 ETF 연 수익률(가상). */
const ETF_ANNUAL_RATE: Record<ProjectionScenario, number> = { GOOD: 0.13, AVG: 0.07, BAD: 0.01 };

function monthlyRate(annualRate: number): number {
  return (1 + annualRate) ** (1 / 12) - 1;
}

function buildScenario(scenario: ProjectionScenario, months: number): ProjectionPoint[] {
  const savingsDeposit = MONTHLY_SAVING * SAVINGS_SHARE;
  const etfDeposit = MONTHLY_SAVING - savingsDeposit;
  const savingsRate = monthlyRate(SAVINGS_ANNUAL_RATE);
  const etfRate = monthlyRate(ETF_ANNUAL_RATE[scenario]);

  let savingsValue = 0;
  let etfValue = 0;
  return Array.from({ length: months }, (_, i) => {
    const month = i + 1;
    savingsValue = savingsValue * (1 + savingsRate) + savingsDeposit;
    etfValue = etfValue * (1 + etfRate) + etfDeposit;

    const principal = MONTHLY_SAVING * month;
    const savingsInterest = Math.round(savingsValue - savingsDeposit * month);
    const etfGain = Math.round(etfValue - etfDeposit * month);
    const profit = savingsInterest + etfGain;
    return { month, principal, savingsInterest, etfGain, profit, total: principal + profit };
  });
}

/** 기간(년)을 고른 mock. 컴포넌트는 최대 30년(360개월)까지 받는다. */
export function buildMockProjection(years: number): Required<ProjectionData> {
  const months = years * 12;
  return { GOOD: buildScenario("GOOD", months), AVG: buildScenario("AVG", months), BAD: buildScenario("BAD", months) };
}

export const MOCK_PROJECTION: Required<ProjectionData> = buildMockProjection(10);

export interface MockScenarioCards {
  scenarios: ScenarioResult[];
  /** 결과 화면 3구간 카드가 읽는 서버 표시 문자열(키 형식은 서버 display와 같다). */
  display: Record<string, string>;
}

const mockWon = (won: number) => `약 ${Math.round(won / WON_PER_MAN).toLocaleString("ko-KR")}만원`;

function calendarMonth(monthsFromStart: number): { year: number; month: number } {
  const index = START_YEAR * 12 + START_MONTH_INDEX + monthsFromStart;
  return { year: Math.floor(index / 12), month: (index % 12) + 1 };
}

/** 결과 화면 3구간 카드(`ScenarioCards`)에 넘길 mock. 서버가 주는 값 모양을 흉내 낸다. */
export function buildMockScenarioCards(data: Required<ProjectionData>): MockScenarioCards {
  const principalEnd = data.AVG.at(-1)?.principal ?? 0;
  const goal = Math.round((principalEnd * GOAL_RATE) / WON_PER_MAN) * WON_PER_MAN;
  const display: Record<string, string> = {};

  const scenarios = SCENARIO_ORDER.map((scenario): ScenarioResult => {
    const points = data[scenario];
    const last = points.at(-1);
    const total = last?.total ?? 0;
    const gap = total - goal;
    const isAchieved = gap >= 0;
    const reach = isAchieved ? points.find((point) => point.total >= goal) : undefined;

    display[`${scenario}.afterTaxAsset`] = mockWon(total);
    display[`${scenario}.gapToGoal`] = `${isAchieved ? "+" : "-"}${mockWon(Math.abs(gap))} ${isAchieved ? "달성" : "미달"}`;
    let goalReachMonth: string | null = null;
    if (reach) {
      const { year, month } = calendarMonth(reach.month);
      goalReachMonth = `${year}-${String(month).padStart(2, "0")}`;
      display[`${scenario}.goalReachMonth`] = `${year}년 ${month}월`;
    }
    return { scenario, afterTaxAsset: total, gapToGoal: gap, isAchieved, goalReachMonth, monthlyPoints: [] };
  });

  return { scenarios, display };
}
