import { applyGoalChoice, emptyForm, type PlanForm } from "./formState";
import { testRules as rules } from "./testRules";
import { validateForm } from "./validateForm";

function validForm(overrides: Partial<PlanForm> = {}): PlanForm {
  return {
    ...applyGoalChoice(emptyForm(), "JEONSE", rules),
    goalAmount: "5000",
    periodYears: "3",
    periodMonths: "0",
    heldAssets: "600",
    monthlySaving: "120",
    ...overrides,
  };
}

describe("validateForm", () => {
  it("올바른 입력은 오류가 없다", () => {
    expect(validateForm(validForm(), rules)).toEqual({});
  });

  it("목표를 고르지 않으면 REQUIRED", () => {
    expect(validateForm({ ...validForm(), goalType: null }, rules).goalType).toBe("REQUIRED");
  });

  it("금액이 비면 REQUIRED, 숫자가 아니면 NOT_NUMBER, 범위 밖이면 OUT_OF_RANGE", () => {
    expect(validateForm(validForm({ goalAmount: "" }), rules).goalAmount).toBe("REQUIRED");
    expect(validateForm(validForm({ goalAmount: "1.5" }), rules).goalAmount).toBe("NOT_NUMBER");
    expect(validateForm(validForm({ goalAmount: "0" }), rules).goalAmount).toBe("OUT_OF_RANGE");
    expect(validateForm(validForm({ goalAmount: "100001" }), rules).goalAmount).toBe("OUT_OF_RANGE");
    expect(validateForm(validForm({ goalAmount: "100,000" }), rules).goalAmount).toBeUndefined();
  });

  it("기간·월 저축액을 모두 모르면 ONE_OF_PERIOD_OR_SAVING", () => {
    expect(validateForm(validForm({ periodKnown: false, savingKnown: false }), rules)).toEqual({
      targetPeriodMonths: "ONE_OF_PERIOD_OR_SAVING",
    });
  });

  it("기간 1년 0개월은 통과, 0년 0개월과 50년 1개월은 OUT_OF_RANGE, 비면 REQUIRED", () => {
    expect(validateForm(validForm({ periodYears: "1", periodMonths: "0" }), rules).targetPeriodMonths).toBeUndefined();
    expect(validateForm(validForm({ periodYears: "0", periodMonths: "0" }), rules).targetPeriodMonths).toBe("OUT_OF_RANGE");
    expect(validateForm(validForm({ periodYears: "50", periodMonths: "1" }), rules).targetPeriodMonths).toBe("OUT_OF_RANGE");
    expect(validateForm(validForm({ periodYears: "", periodMonths: "" }), rules).targetPeriodMonths).toBe("REQUIRED");
  });

  it("월 저축액은 알아요일 때만 검사한다", () => {
    expect(validateForm(validForm({ monthlySaving: "" }), rules).monthlySaving).toBe("REQUIRED");
    expect(validateForm(validForm({ monthlySaving: "", savingKnown: false }), rules).monthlySaving).toBeUndefined();
    expect(validateForm(validForm({ monthlySaving: "0" }), rules).monthlySaving).toBe("OUT_OF_RANGE");
  });

  it("이모지 100자 목표명은 통과하고 101자는 TOO_LONG, 공백만은 BLANK, 비면 REQUIRED", () => {
    const custom = (name: string) => validForm({ goalType: "CUSTOM", goalCustomName: name });
    expect(validateForm(custom(` ${"😀".repeat(100)} `), rules).goalCustomName).toBeUndefined();
    expect(validateForm(custom(`${"😀".repeat(100)}가`), rules).goalCustomName).toBe("TOO_LONG");
    expect(validateForm(custom("   "), rules).goalCustomName).toBe("BLANK");
    expect(validateForm(custom(""), rules).goalCustomName).toBe("REQUIRED");
  });

  it("금리 3.255는 INVALID_SCALE, 50.01은 OUT_OF_RANGE, 비우면 통과", () => {
    expect(validateForm(validForm({ savingsRatePct: "3.255" }), rules).savingsRatePct).toBe("INVALID_SCALE");
    expect(validateForm(validForm({ savingsRatePct: "50.01" }), rules).savingsRatePct).toBe("OUT_OF_RANGE");
    expect(validateForm(validForm({ savingsRatePct: "" }), rules).savingsRatePct).toBeUndefined();
  });

  it("기존 납입액 합계가 월 저축액보다 크면 EXISTING_EXCEEDS_SAVING", () => {
    const errors = validateForm(validForm({ existingOn: true, existingSavings: "100", existingEtf: "30", ratioChoice: "BASE" }), rules);
    expect(errors.existingSavingsMonthly).toBe("EXISTING_EXCEEDS_SAVING");
  });

  it("기존 납입액이 있고 비중 선택이 없으면 RATIO_CHOICE_REQUIRED", () => {
    const errors = validateForm(validForm({ existingOn: true, existingSavings: "100", existingEtf: "20" }), rules);
    expect(errors).toEqual({ ratioChoice: "RATIO_CHOICE_REQUIRED" });
  });

  it("토글을 켜도 납입액이 비어 있으면 오류가 없다", () => {
    expect(validateForm(validForm({ existingOn: true }), rules)).toEqual({});
  });
});
