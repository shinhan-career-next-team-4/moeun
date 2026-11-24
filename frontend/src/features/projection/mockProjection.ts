import type { ProjectionData, ProjectionPoint, ProjectionScenario } from "./types";

/**
 * 연동 전 화면 확인용 mock 데이터(#298). 실제 값은 서버(계산 엔진)가 계산해 내려 준다.
 *
 * 월 50만원을 적금 50% : ETF 50%로 120개월 모으는 가상의 계획이다. 이 파일은 **mock이라서** 값을 직접 만든다.
 * 화면 컴포넌트는 이런 계산을 하지 않고, 받은 값을 그대로 그린다.
 */

const MONTHS = 120;
const MONTHLY_SAVING = 500_000;
const SAVINGS_SHARE = 0.5;
const SAVINGS_ANNUAL_RATE = 0.032;

/** 시나리오별 ETF 연 수익률(가상). */
const ETF_ANNUAL_RATE: Record<ProjectionScenario, number> = { LOW: 0.01, BASE: 0.07, HIGH: 0.13 };

function monthlyRate(annualRate: number): number {
  return (1 + annualRate) ** (1 / 12) - 1;
}

function buildScenario(scenario: ProjectionScenario): ProjectionPoint[] {
  const savingsDeposit = MONTHLY_SAVING * SAVINGS_SHARE;
  const etfDeposit = MONTHLY_SAVING - savingsDeposit;
  const savingsRate = monthlyRate(SAVINGS_ANNUAL_RATE);
  const etfRate = monthlyRate(ETF_ANNUAL_RATE[scenario]);

  let savingsValue = 0;
  let etfValue = 0;
  return Array.from({ length: MONTHS }, (_, i) => {
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

export const MOCK_PROJECTION: ProjectionData = {
  LOW: buildScenario("LOW"),
  BASE: buildScenario("BASE"),
  HIGH: buildScenario("HIGH"),
};
