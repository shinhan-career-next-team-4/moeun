import { describe, expect, it } from "vitest";
import { niceTicks, yearTickMonths } from "../axis";

describe("세로축 눈금 niceTicks (#298)", () => {
  it("91,000,000이면 0부터 2,500만 간격의 눈금 5개다", () => {
    expect(niceTicks(91_000_000)).toEqual([0, 25_000_000, 50_000_000, 75_000_000, 100_000_000]);
  });

  it.each([4_000_000, 6_000_000, 7_300_000, 9_000_000, 12_000_000, 20_000_000, 91_000_000, 250_000_000, 1_234_567_890])(
    "%i원까지 덮는 눈금은 0부터 시작하고 4~5개이며 최댓값을 넘는다",
    (max) => {
      const ticks = niceTicks(max);

      expect(ticks[0]).toBe(0);
      expect(ticks.length).toBeGreaterThanOrEqual(4);
      expect(ticks.length).toBeLessThanOrEqual(5);
      expect(ticks[ticks.length - 1]).toBeGreaterThanOrEqual(max);
      expect([...ticks].sort((a, b) => a - b)).toEqual(ticks);
    },
  );

  it("값이 아주 작거나 0이어도 0부터 시작하는 눈금을 돌려준다", () => {
    const ticks = niceTicks(0);

    expect(ticks[0]).toBe(0);
    expect(ticks.length).toBeGreaterThanOrEqual(4);
  });
});

describe("가로축 눈금 월 yearTickMonths (#298)", () => {
  it("120개월이면 1년부터 10년까지 12개월 간격이다", () => {
    expect(yearTickMonths(120)).toEqual([12, 24, 36, 48, 60, 72, 84, 96, 108, 120]);
  });

  it("36개월이면 1·2·3년이다", () => {
    expect(yearTickMonths(36)).toEqual([12, 24, 36]);
  });

  it("1년이 안 되면 눈금이 없다", () => {
    expect(yearTickMonths(6)).toEqual([]);
  });

  it("끝이 12의 배수가 아니면 마지막 완전한 해까지만 찍는다", () => {
    expect(yearTickMonths(126)).toEqual([12, 24, 36, 48, 60, 72, 84, 96, 108, 120]);
  });

  it("자리가 좁아 라벨을 5개까지만 둘 수 있으면 2년 간격으로 솎는다", () => {
    expect(yearTickMonths(120, 5)).toEqual([24, 48, 72, 96, 120]);
  });

  it("자리가 아주 좁아도 마지막 해 눈금은 남는다(라벨 1개)", () => {
    expect(yearTickMonths(120, 1)).toEqual([120]);
  });

  it("30년(360개월)이면 눈금이 10개를 넘지 않게 3년 간격이다", () => {
    const months = yearTickMonths(360);

    expect(months.length).toBeLessThanOrEqual(10);
    expect(months[0]).toBe(36);
    expect(months[months.length - 1]).toBe(360);
  });
});
