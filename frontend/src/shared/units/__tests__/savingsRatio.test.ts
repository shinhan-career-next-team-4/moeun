import { describe, expect, it } from "vitest";
import { savingsRatioFromAmounts } from "../savingsRatio";

/** 계약 C12a-5-6 (g)의 9쌍(만원 단위 입력). D-087의 정수 HALF_UP 식을 확인한다. */
const CASES: [string, string, number | "empty"][] = [
  ["100", "20", 83],
  ["1", "1", 50],
  ["1", "2", 33],
  ["2", "1", 67],
  ["1", "7", 13],
  ["7", "1", 88],
  ["0", "5", 0],
  ["5", "0", 100],
  ["0", "0", "empty"],
];

describe("savingsRatioFromAmounts (#127, D-087)", () => {
  it.each(CASES)("적금 %s만원 · ETF %s만원 → %s", (bank, etf, expected) => {
    const result = savingsRatioFromAmounts(bank, etf);
    if (expected === "empty") {
      expect(result).toEqual({ kind: "empty" });
      return;
    }
    expect(result).toEqual({ kind: "ratio", savingsRatioPct: expected });
  });

  it("빈 칸은 0으로 보고, 둘 다 비면 칸 비움이다", () => {
    expect(savingsRatioFromAmounts("", "")).toEqual({ kind: "empty" });
    expect(savingsRatioFromAmounts("", "10")).toEqual({ kind: "ratio", savingsRatioPct: 0 });
    expect(savingsRatioFromAmounts("10", "")).toEqual({ kind: "ratio", savingsRatioPct: 100 });
  });

  it("숫자로 읽을 수 없으면 invalid다", () => {
    expect(savingsRatioFromAmounts("백만", "10")).toEqual({ kind: "invalid" });
    expect(savingsRatioFromAmounts("-5", "10")).toEqual({ kind: "invalid" });
  });

  it("경계값도 정수 HALF_UP이다(부동소수 반올림과 다른 경우)", () => {
    // 0.5%p 경계는 올린다: 1/8 = 12.5 → 13
    expect(savingsRatioFromAmounts("1", "7")).toEqual({ kind: "ratio", savingsRatioPct: 13 });
    // 3/8 = 37.5 → 38
    expect(savingsRatioFromAmounts("3", "5")).toEqual({ kind: "ratio", savingsRatioPct: 38 });
  });
});
