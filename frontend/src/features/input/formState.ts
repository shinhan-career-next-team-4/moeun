import { COPY } from "../../shared/copy/copy";
import {
  isBlank,
  manwonToWon,
  parseDecimalInput,
  parseNonNegativeInt,
  splitMonths,
  toMonths,
  wonToManwon,
} from "../../shared/units/units";
import type { ResultPlanBody } from "../result/types";
import type { FormErrors, FormField, GoalType, PlanForm, PutPlanRequest } from "./types";

export const FIELD_ORDER: FormField[] = [
  "goalType",
  "goalAmount",
  "targetPeriodMonths",
  "heldAssets",
  "monthlySavingAmount",
  "savingsRateInputPct",
];

/** D-082: 보유 자산 칸은 0으로 시작하고, 나머지 금액·기간 칸은 비워 둔다. */
export function emptyForm(): PlanForm {
  return {
    goalType: null,
    goalAmount: "",
    periodYears: "",
    periodMonths: "",
    heldAssets: "0",
    monthlySavingAmount: "",
    savingsRateInputPct: "",
  };
}

/** 입력 수정 진입(#51, C12a-7): 저장된 값을 그대로 채운다. 금리가 null이면 비운다. */
export function formFromPlan(plan: ResultPlanBody): PlanForm {
  const { years, months } = splitMonths(plan.targetPeriodMonths);
  return {
    goalType: plan.goalType as GoalType,
    goalAmount: wonToManwon(plan.goalAmount),
    periodYears: String(years),
    periodMonths: String(months),
    heldAssets: wonToManwon(plan.heldAssets),
    monthlySavingAmount: wonToManwon(plan.monthlySavingAmount),
    savingsRateInputPct: plan.savingsRateInputPct === null ? "" : String(plan.savingsRateInputPct),
  };
}

/** C12a-5-4: 프론트 사전 검사는 비어 있음(REQUIRED)과 입력 형식만 본다. 범위 수치는 서버 422에 맡긴다. */
export function validateClientForm(form: PlanForm): FormErrors {
  const errors: FormErrors = {};
  if (form.goalType === null) errors.goalType = COPY["validation.REQUIRED"];

  if (isBlank(form.goalAmount)) errors.goalAmount = COPY["validation.REQUIRED"];
  else if (!manwonToWon(form.goalAmount).ok) errors.goalAmount = COPY["input.invalidAmountFormat"];

  if (isBlank(form.heldAssets)) errors.heldAssets = COPY["validation.REQUIRED"];
  else if (!manwonToWon(form.heldAssets).ok) errors.heldAssets = COPY["input.invalidAmountFormat"];

  if (isBlank(form.periodYears) && isBlank(form.periodMonths)) {
    errors.targetPeriodMonths = COPY["validation.REQUIRED"];
  } else {
    const yearsOk = isBlank(form.periodYears) || parseNonNegativeInt(form.periodYears).ok;
    const monthsOk = isBlank(form.periodMonths) || parseNonNegativeInt(form.periodMonths).ok;
    if (!yearsOk || !monthsOk) errors.targetPeriodMonths = COPY["input.invalidAmountFormat"];
  }

  if (isBlank(form.monthlySavingAmount)) errors.monthlySavingAmount = COPY["validation.REQUIRED"];
  else if (!manwonToWon(form.monthlySavingAmount).ok) errors.monthlySavingAmount = COPY["input.invalidAmountFormat"];

  if (!isBlank(form.savingsRateInputPct) && !parseDecimalInput(form.savingsRateInputPct).ok) {
    errors.savingsRateInputPct = COPY["input.invalidAmountFormat"];
  }
  return errors;
}

export function buildPutPlanBody(form: PlanForm, expectedRevision: number | null): PutPlanRequest {
  if (form.goalType === null) throw new Error("goalType is required");
  const goalAmount = manwonToWon(form.goalAmount);
  const heldAssets = manwonToWon(form.heldAssets);
  const monthlySaving = manwonToWon(form.monthlySavingAmount);
  if (!goalAmount.ok || !heldAssets.ok || !monthlySaving.ok) {
    throw new Error("금액 형식이 올바르지 않습니다");
  }
  const years = isBlank(form.periodYears) ? 0 : (parseNonNegativeInt(form.periodYears) as { ok: true; value: number }).value;
  const months = isBlank(form.periodMonths)
    ? 0
    : (parseNonNegativeInt(form.periodMonths) as { ok: true; value: number }).value;
  const rate = isBlank(form.savingsRateInputPct)
    ? null
    : (parseDecimalInput(form.savingsRateInputPct) as { ok: true; value: number }).value;

  return {
    expectedRevision,
    goalType: form.goalType,
    goalCustomName: null,
    goalAmount: goalAmount.won,
    targetPeriodMonths: toMonths(years, months),
    heldAssets: heldAssets.won,
    monthlySavingAmount: monthlySaving.won,
    savingsRateInputPct: rate,
  };
}
