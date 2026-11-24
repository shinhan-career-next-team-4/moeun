import { describe, expect, it } from "vitest";

import { formatMonthLabel } from "../period";

describe("formatMonthLabel (눈금 날짜 라벨)", () => {
  it("시작월에 개월 수를 더해 YYYY.MM으로 쓴다", () => {
    expect(formatMonthLabel("2026-11", 6)).toBe("2027.05");
    expect(formatMonthLabel("2026-11", 12)).toBe("2027.11");
    expect(formatMonthLabel("2026-11", 1)).toBe("2026.12");
    expect(formatMonthLabel("2026-11", 120)).toBe("2036.11");
  });

  it("형식이 다른 시작월이면 null이다", () => {
    expect(formatMonthLabel("2026/11", 6)).toBeNull();
  });
});
