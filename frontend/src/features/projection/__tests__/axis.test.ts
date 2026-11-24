import { describe, expect, it } from "vitest";
import { niceTicks, axisTickMonths } from "../axis";

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

describe("가로축 눈금 월 axisTickMonths (#298)", () => {
  it("120개월이면 1년부터 10년까지 12개월 간격이다", () => {
    expect(axisTickMonths(120)).toEqual([12, 24, 36, 48, 60, 72, 84, 96, 108, 120]);
  });

  it("36개월이면 6개월 간격이다", () => {
    expect(axisTickMonths(36)).toEqual([6, 12, 18, 24, 30, 36]);
  });

  it("12개월 미만이면 1개월 간격이다", () => {
    expect(axisTickMonths(6)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(axisTickMonths(11)).toHaveLength(11);
  });

  it("12~23개월이면 2개월 간격이다", () => {
    expect(axisTickMonths(12)).toEqual([2, 4, 6, 8, 10, 12]);
    expect(axisTickMonths(23)).toEqual([2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22]);
  });

  it("24~59개월이면 6개월 간격이다", () => {
    expect(axisTickMonths(24)).toEqual([6, 12, 18, 24]);
    expect(axisTickMonths(59)).toEqual([6, 12, 18, 24, 30, 36, 42, 48, 54]);
  });

  it("60개월부터는 1년 간격이다", () => {
    expect(axisTickMonths(60)).toEqual([12, 24, 36, 48, 60]);
  });

  it("끝이 12의 배수가 아니면 마지막 완전한 해까지만 찍는다", () => {
    expect(axisTickMonths(126)).toEqual([12, 24, 36, 48, 60, 72, 84, 96, 108, 120]);
  });

  it("자리가 좁아 라벨을 5개까지만 둘 수 있으면 2년 간격으로 솎는다", () => {
    expect(axisTickMonths(120, 5)).toEqual([24, 48, 72, 96, 120]);
  });

  it("자리가 아주 좁아도 마지막 해 눈금은 남는다(라벨 1개)", () => {
    expect(axisTickMonths(120, 1)).toEqual([120]);
  });

  it("30년(360개월)이면 눈금이 10개를 넘지 않게 3년 간격이다", () => {
    const months = axisTickMonths(360);

    expect(months.length).toBeLessThanOrEqual(10);
    expect(months[0]).toBe(36);
    expect(months[months.length - 1]).toBe(360);
  });
});
