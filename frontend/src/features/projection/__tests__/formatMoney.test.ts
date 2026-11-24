import { describe, expect, it } from "vitest";
import { formatMoneyShort } from "../formatMoney";

describe("금액 축약 formatMoneyShort (#298)", () => {
  it.each([
    [59_999_999, "6,000만"],
    [120_000_000, "1억 2,000만"],
    [100_000_000, "1억"],
    [30_000_000, "3,000만"],
    [91_000_000, "9,100만"],
    [1_234_567_890, "12억 3,457만"],
    [99_995_000, "1억"], // 만 단위로 반올림하면 10,000만 = 1억
    [5_000, "1만"],
    [4_999, "0"],
    [0, "0"],
  ])("%i원 → %s", (won, expected) => {
    expect(formatMoneyShort(won)).toBe(expected);
  });

  it("음수는 부호를 앞에 붙인다", () => {
    expect(formatMoneyShort(-30_000_000)).toBe("-3,000만");
  });

  it("반올림하면 0이 되는 음수는 부호 없이 0이다", () => {
    expect(formatMoneyShort(-4_999)).toBe("0");
  });
});
