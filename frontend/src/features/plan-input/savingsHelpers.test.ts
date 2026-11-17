import { initialMonthlySavingManwon, keepCurrentRatio, splitByRatio } from "./savingsHelpers";
import { testRules as rules } from "./testRules";

describe("initialMonthlySavingManwon", () => {
  it("남은 금액을 기간으로 나눠 만원 단위로 내린다(기능정의서 AC-01~05)", () => {
    expect(initialMonthlySavingManwon(10000, 0, 24, rules)).toBe(416);
    expect(initialMonthlySavingManwon(3000, 0, 12, rules)).toBe(250);
    expect(initialMonthlySavingManwon(3000, 0, 36, rules)).toBe(83);
    expect(initialMonthlySavingManwon(200, 0, 6, rules)).toBe(33);
    expect(initialMonthlySavingManwon(5000, 600, 36, rules)).toBe(122);
  });

  it("이미 목표를 넘으면 비워 두고, 최대를 넘으면 최대로 맞춘다", () => {
    expect(initialMonthlySavingManwon(1000, 2000, 12, rules)).toBeNull();
    expect(initialMonthlySavingManwon(100000, 0, 1, rules)).toBe(1000);
  });
});

describe("비중", () => {
  it("지금 비중은 정수로 반올림한다", () => {
    expect(keepCurrentRatio(100, 20)).toBe(83);
    expect(keepCurrentRatio(70, 80)).toBe(47);
  });

  it("월 저축액을 비중대로 나누고 나머지는 ETF로 둔다", () => {
    expect(splitByRatio(120, 50)).toEqual({ savings: 60, etf: 60 });
    expect(splitByRatio(101, 50)).toEqual({ savings: 51, etf: 50 });
  });
});
