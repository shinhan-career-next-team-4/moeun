import { describe, expect, it } from "vitest";
import { formatFieldError, goalLabel, manwonToWon, splitMonths, toMonths, wonToManwon } from "../units";

describe("만원 ↔ 원 문자열 변환 (C12a-5-6 (d))", () => {
  it("소수점 오른쪽 네 칸 이동으로 정확히 변환한다", () => {
    expect(manwonToWon("30")).toEqual({ ok: true, won: 300_000 });
    expect(manwonToWon("34.5")).toEqual({ ok: true, won: 345_000 });
    expect(manwonToWon("0.0001")).toEqual({ ok: true, won: 1 });
    expect(manwonToWon("1.1")).toEqual({ ok: true, won: 11_000 });
    expect(manwonToWon("0")).toEqual({ ok: true, won: 0 });
    expect(manwonToWon("1,000")).toEqual({ ok: true, won: 10_000_000 });
  });

  it("소수 5자리 이상이나 형식 오류를 거절한다", () => {
    expect(manwonToWon("12.34567")).toEqual({ ok: false });
    expect(manwonToWon("abc")).toEqual({ ok: false });
    expect(manwonToWon("-5")).toEqual({ ok: false });
    expect(manwonToWon("")).toEqual({ ok: false });
  });

  it("원 정수를 만원 문자열로 되돌린다", () => {
    expect(wonToManwon(300_000)).toBe("30");
    expect(wonToManwon(345_000)).toBe("34.5");
    expect(wonToManwon(1)).toBe("0.0001");
    expect(wonToManwon(0)).toBe("0");
  });
});

describe("기간 변환 (C12a-5-6 (e))", () => {
  it("년·개월과 총 개월을 정확히 오간다", () => {
    expect(toMonths(3, 0)).toBe(36);
    expect(splitMonths(36)).toEqual({ years: 3, months: 0 });
    expect(splitMonths(360)).toEqual({ years: 30, months: 0 });
    expect(splitMonths(1)).toEqual({ years: 0, months: 1 });
  });
});

describe("422 범위 문구 포맷 (C12a-5-6 (f))", () => {
  it("KRW 0은 0원, 그 밖은 만원 단위로 표시한다", () => {
    expect(
      formatFieldError({
        field: "heldAssets",
        reason: "OUT_OF_RANGE",
        min: 0,
        max: 10_000_000_000,
        unit: "KRW",
      }),
    ).toBe("0원~1,000,000만원 사이로 입력해 주세요");

    expect(
      formatFieldError({
        field: "monthlySavingAmount",
        reason: "OUT_OF_RANGE",
        min: 10_000,
        max: 10_000_000,
        unit: "KRW",
      }),
    ).toBe("1만원~1,000만원 사이로 입력해 주세요");
  });

  it("MONTH, PCT, CHAR, NOT_INTEGER, TOO_MANY_DECIMALS를 포맷한다", () => {
    expect(
      formatFieldError({ field: "targetPeriodMonths", reason: "OUT_OF_RANGE", min: 1, max: 360, unit: "MONTH" }),
    ).toBe("0년 1개월~30년 0개월 사이로 입력해 주세요");
    expect(
      formatFieldError({ field: "savingsRateInputPct", reason: "OUT_OF_RANGE", min: 0, max: 20, unit: "PCT" }),
    ).toBe("연 0%~연 20% 사이로 입력해 주세요");
    expect(formatFieldError({ field: "goalCustomName", reason: "OUT_OF_RANGE", min: 1, max: 100, unit: "CHAR" })).toBe(
      "1자~100자 사이로 입력해 주세요",
    );
    expect(formatFieldError({ field: "goalAmount", reason: "NOT_INTEGER" })).toBe("정수로 입력해 주세요");
    expect(formatFieldError({ field: "savingsRateInputPct", reason: "TOO_MANY_DECIMALS", scale: 2 })).toBe(
      "소수 2자리까지 입력할 수 있어요",
    );
  });
});

describe("goalLabel (C12a-5-1)", () => {
  it("알려진 코드는 문구표를, 모르는 코드는 코드 문자열을 그대로 돌려준다", () => {
    expect(goalLabel("HOUSE")).toBe("내 집 마련");
    expect(goalLabel("FUTURE_GOAL")).toBe("FUTURE_GOAL");
  });
});
