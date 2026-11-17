import type { InputRules } from "@/lib/api/types";

const WON_PER_MANWON = 10_000;

/**
 * 월 저축액 자동 초기값(만원): floor(max(목표 − 보유, 0) ÷ 기간).
 * 이자·수익을 반영하지 않은 입력 보조값이다. 0이면 비워 두고(null), 최대를 넘으면 최대로 맞춘다.
 */
export function initialMonthlySavingManwon(goalManwon: number, heldManwon: number, months: number, rules: InputRules): number | null {
  const value = Math.floor(Math.max(goalManwon - heldManwon, 0) / months);
  if (value === 0) {
    return null;
  }
  return Math.min(value, rules.fields.monthlySaving.max / WON_PER_MANWON);
}

/** 지금 비중 유지(CF-08 B안): 적금 ÷ 합계, 정수 반올림 */
export function keepCurrentRatio(savingsManwon: number, etfManwon: number): number {
  return Math.round((savingsManwon * 100) / (savingsManwon + etfManwon));
}

/** 월 저축액을 적금 비중대로 나눈다. ETF는 나머지라 합계가 항상 맞는다 */
export function splitByRatio(monthlyManwon: number, savingsPct: number): { savings: number; etf: number } {
  const savings = Math.round((monthlyManwon * savingsPct) / 100);
  return { savings, etf: monthlyManwon - savings };
}
