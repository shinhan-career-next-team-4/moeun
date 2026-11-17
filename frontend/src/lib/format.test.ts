import {
  formatGoalDiff,
  formatManwon,
  formatMoney,
  formatPeriod,
  formatRatio,
  formatYearMonth,
} from "./format";

describe("formatMoney", () => {
  it("천원 단위 반올림 후 만원 단위로 '약 N만원'을 만든다", () => {
    expect(formatMoney(49_800_000)).toBe("약 4,980만원");
    expect(formatMoney(49_806_000)).toBe("약 4,981만원");
    expect(formatMoney(49_804_400)).toBe("약 4,980만원");
    expect(formatMoney(120_000_000)).toBe("약 12,000만원");
  });
});

describe("formatGoalDiff", () => {
  it("부호와 달성·미달 라벨을 붙이고 0은 달성이다", () => {
    expect(formatGoalDiff(1_500_000)).toBe("+150만원 달성");
    expect(formatGoalDiff(-200_000)).toBe("−20만원 미달");
    expect(formatGoalDiff(0)).toBe("0만원 달성");
  });
});

describe("formatPeriod", () => {
  it("'n년 n개월'로 쓰고 0인 쪽은 생략한다", () => {
    expect(formatPeriod(36)).toBe("3년");
    expect(formatPeriod(18)).toBe("1년 6개월");
    expect(formatPeriod(8)).toBe("8개월");
    expect(formatPeriod(null)).toBe("기간 미정");
  });
});

describe("formatRatio", () => {
  it("'적금 a : ETF b' 정수로 쓴다", () => {
    expect(formatRatio(83)).toBe("적금 83 : ETF 17");
    expect(formatRatio(50)).toBe("적금 50 : ETF 50");
  });
});

describe("formatManwon", () => {
  it("정확한 만원 값을 쉼표와 함께 쓴다", () => {
    expect(formatManwon(50_000_000)).toBe("5,000만원");
    expect(formatManwon(0)).toBe("0만원");
  });
});

describe("formatYearMonth", () => {
  it("시작 월에 개월 수를 더해 'yyyy.MM'으로 쓴다", () => {
    expect(formatYearMonth("2026-11", 36)).toBe("2029.11");
    expect(formatYearMonth("2026-11", 2)).toBe("2027.01");
  });
});
