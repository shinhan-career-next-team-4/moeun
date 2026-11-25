import { describe, expect, it } from "vitest";
import { toYearly } from "../selectPoints";
import type { ProjectionPoint } from "../types";

function point(month: number): ProjectionPoint {
  return { month, principal: month * 10, savingsInterest: month, etfGain: month * 2, profit: month * 3, total: month * 13 };
}

const monthly = Array.from({ length: 120 }, (_, i) => point(i + 1));

describe("연별 데이터 toYearly (#298)", () => {
  it("첫 점과 12개월마다의 점만 고른다", () => {
    expect(toYearly(monthly).map((p) => p.month)).toEqual([1, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120]);
  });

  it("점의 값은 그대로 두고 더하거나 고치지 않는다", () => {
    const yearly = toYearly(monthly);

    expect(yearly[3]).toBe(monthly[35]); // 36개월째 점 그대로
  });

  it("점이 하나뿐이면 그대로 돌려준다", () => {
    expect(toYearly([point(1)]).map((p) => p.month)).toEqual([1]);
  });

  it("빈 배열은 빈 배열이다", () => {
    expect(toYearly([])).toEqual([]);
  });
});
