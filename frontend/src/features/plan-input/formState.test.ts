import type { PlanView } from "@/lib/api/types";

import { applyGoalChoice, autoFillMonthlySaving, emptyForm, formFromPlan, toDto, type PlanForm } from "./formState";
import { testRules as rules } from "./testRules";

const savedPlan: PlanView = {
  goalType: "CUSTOM",
  goalCustomName: "차 사기",
  goalAmount: 50_000_000,
  targetPeriodMonths: 18,
  heldAssets: 6_000_000,
  monthlySaving: 1_500_000,
  savingsRatePct: 3.25,
  existingSavingsMonthly: 1_000_000,
  existingEtfMonthly: 200_000,
  ratioChoice: "KEEP_CURRENT",
  savingsRatioPct: 83,
  calcStartMonth: "2026-11",
  baselineScenario: "AVG",
  revision: 2,
  canUndo: true,
  updatedAt: "2026-11-16T00:00:00Z",
};

describe("applyGoalChoice", () => {
  it("목표를 바꾸면 기본 금액·기간으로 바꾸고 보유 자산은 유지한다", () => {
    const form: PlanForm = { ...emptyForm(), heldAssets: "600", monthlySaving: "99", monthlySavingTouched: true };
    const next = applyGoalChoice(form, "JEONSE", rules);
    expect(next.goalType).toBe("JEONSE");
    expect(next.goalAmount).toBe("10000");
    expect(next.periodYears).toBe("2");
    expect(next.periodMonths).toBe("0");
    expect(next.heldAssets).toBe("600");
    expect(next.monthlySavingTouched).toBe(false);
  });

  it("직접 입력은 직전 금액·기간을 유지한다", () => {
    const form = applyGoalChoice(emptyForm(), "WEDDING", rules);
    const next = applyGoalChoice(form, "CUSTOM", rules);
    expect(next.goalAmount).toBe("3000");
    expect(next.periodYears).toBe("1");
  });
});

describe("autoFillMonthlySaving", () => {
  it("직접 고치기 전에는 금액·보유·기간으로 채운다", () => {
    const form = { ...applyGoalChoice(emptyForm(), "JEONSE", rules), heldAssets: "0" };
    expect(autoFillMonthlySaving(form, rules).monthlySaving).toBe("416");
  });

  it("직접 고친 뒤에는 바꾸지 않는다", () => {
    const form = { ...applyGoalChoice(emptyForm(), "JEONSE", rules), heldAssets: "0", monthlySaving: "300", monthlySavingTouched: true };
    expect(autoFillMonthlySaving(form, rules).monthlySaving).toBe("300");
  });

  it("값이 숫자가 아니면 그대로 둔다", () => {
    const form = { ...applyGoalChoice(emptyForm(), "JEONSE", rules), heldAssets: "abc", monthlySaving: "7" };
    expect(autoFillMonthlySaving(form, rules).monthlySaving).toBe("7");
  });
});

describe("formFromPlan → toDto", () => {
  it("저장된 계획을 폼으로 바꿨다가 DTO로 돌리면 같은 값이다(월 저축액 포함)", () => {
    const form = formFromPlan(savedPlan);
    expect(form.monthlySavingTouched).toBe(true);
    expect(form.periodYears).toBe("1");
    expect(form.periodMonths).toBe("6");
    expect(toDto(form)).toEqual({
      goalType: "CUSTOM",
      goalCustomName: "차 사기",
      goalAmount: 50_000_000,
      targetPeriodMonths: 18,
      heldAssets: 6_000_000,
      monthlySaving: 1_500_000,
      savingsRatePct: 3.25,
      existingSavingsMonthly: 1_000_000,
      existingEtfMonthly: 200_000,
      ratioChoice: "KEEP_CURRENT",
    });
  });

  it("기간·월 저축액을 모르면 null, 기존 저축 합계 0이면 토글 OFF로 보낸다", () => {
    const form: PlanForm = {
      ...formFromPlan(savedPlan),
      goalType: "TRAVEL",
      periodKnown: false,
      existingSavings: "0",
      existingEtf: "",
      savingsRatePct: "",
    };
    const dto = toDto(form);
    expect(dto.goalCustomName).toBeNull();
    expect(dto.targetPeriodMonths).toBeNull();
    expect(dto.savingsRatePct).toBeNull();
    expect(dto.existingSavingsMonthly).toBeNull();
    expect(dto.existingEtfMonthly).toBeNull();
    expect(dto.ratioChoice).toBeNull();
  });
});
