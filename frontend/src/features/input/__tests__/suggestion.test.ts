import { describe, expect, it } from "vitest";
import { suggestMonthlySaving, suggestPeriodMonths } from "../suggestion";

// D-084: 수익·이자를 빼고 (목표 금액 − 보유 자산)을 나눈 대략값. 만원 단위 올림.
describe("추천 월 저축액 (FR-INP-06, D-084)", () => {
  it("3,000만원·보유 600만원·3년이면 67만원을 추천한다", () => {
    expect(suggestMonthlySaving({ goalAmountManwon: "3000", heldAssetsManwon: "600", periodMonths: 36 })).toEqual({
      kind: "amount",
      manwon: "67",
    });
  });

  it("나누어떨어지지 않으면 올림한다", () => {
    expect(suggestMonthlySaving({ goalAmountManwon: "100", heldAssetsManwon: "0", periodMonths: 3 })).toEqual({
      kind: "amount",
      manwon: "34",
    });
  });

  it("1,000만원을 넘으면 채우지 않고 안내만 한다", () => {
    expect(suggestMonthlySaving({ goalAmountManwon: "100000", heldAssetsManwon: "0", periodMonths: 1 })).toEqual({
      kind: "tooLarge",
    });
  });

  it("보유 자산이 목표 이상이면 추천하지 않는다(FR-RES-01이 먼저다)", () => {
    expect(suggestMonthlySaving({ goalAmountManwon: "1000", heldAssetsManwon: "1000", periodMonths: 36 })).toEqual({
      kind: "overGoal",
    });
  });

  it("값이 모자라거나 형식이 틀리면 추천하지 않는다", () => {
    expect(suggestMonthlySaving({ goalAmountManwon: "", heldAssetsManwon: "0", periodMonths: 36 })).toEqual({
      kind: "needsInput",
    });
    expect(suggestMonthlySaving({ goalAmountManwon: "3000", heldAssetsManwon: "600", periodMonths: 0 })).toEqual({
      kind: "needsInput",
    });
    expect(suggestMonthlySaving({ goalAmountManwon: "삼천", heldAssetsManwon: "0", periodMonths: 36 })).toEqual({
      kind: "needsInput",
    });
  });
});

describe("추천 기간 (FR-INP-04, D-084)", () => {
  it("3,000만원·보유 600만원·월 60만원이면 40개월을 추천한다", () => {
    expect(suggestPeriodMonths({ goalAmountManwon: "3000", heldAssetsManwon: "600", monthlySavingManwon: "60" })).toEqual(
      { kind: "months", months: 40 },
    );
  });

  it("나누어떨어지지 않으면 올림한다", () => {
    expect(suggestPeriodMonths({ goalAmountManwon: "100", heldAssetsManwon: "0", monthlySavingManwon: "30" })).toEqual({
      kind: "months",
      months: 4,
    });
  });

  it("360개월을 넘으면 채우지 않고 안내만 한다", () => {
    expect(suggestPeriodMonths({ goalAmountManwon: "100000", heldAssetsManwon: "0", monthlySavingManwon: "10" })).toEqual(
      { kind: "tooLong" },
    );
  });

  it("360개월 경계는 추천한다", () => {
    expect(suggestPeriodMonths({ goalAmountManwon: "3600", heldAssetsManwon: "0", monthlySavingManwon: "10" })).toEqual({
      kind: "months",
      months: 360,
    });
  });

  it("보유 자산이 목표 이상이면 추천하지 않는다", () => {
    expect(suggestPeriodMonths({ goalAmountManwon: "1000", heldAssetsManwon: "1200", monthlySavingManwon: "30" })).toEqual(
      { kind: "overGoal" },
    );
  });

  it("월 저축액이 0이거나 비어 있으면 추천하지 않는다", () => {
    expect(suggestPeriodMonths({ goalAmountManwon: "3000", heldAssetsManwon: "0", monthlySavingManwon: "0" })).toEqual({
      kind: "needsInput",
    });
    expect(suggestPeriodMonths({ goalAmountManwon: "3000", heldAssetsManwon: "0", monthlySavingManwon: "" })).toEqual({
      kind: "needsInput",
    });
  });
});
