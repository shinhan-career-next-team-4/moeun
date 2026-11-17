import type { ErrorCode, FieldRule, InputRules, PlanInputDto } from "@/lib/api/types";

import { periodMonthsOf, type PlanForm } from "./formState";
import { decimalPlaces, isBlank, parseDecimal, parseManwon } from "./parseManwon";

export type FormField = keyof PlanInputDto;
export type FormErrorCode = ErrorCode | "NOT_NUMBER";
export type FormErrors = Partial<Record<FormField, FormErrorCode>>;

/** 화면 순서. 오류 요약과 첫 오류 칸 포커스에 쓴다 */
export const FIELD_ORDER: FormField[] = [
  "goalType",
  "goalCustomName",
  "goalAmount",
  "heldAssets",
  "targetPeriodMonths",
  "monthlySaving",
  "savingsRatePct",
  "existingSavingsMonthly",
  "existingEtfMonthly",
  "ratioChoice",
];

/** 백엔드와 같은 순서(소수 자리 → 단위 → 범위)로 첫 위반을 돌려준다 */
function checkRule(rule: FieldRule, value: number, places = 0): ErrorCode | undefined {
  if (rule.scale !== undefined && places > rule.scale) return "INVALID_SCALE";
  if (rule.step !== undefined && value % rule.step !== 0) return "NOT_UNIT";
  if (value < rule.min || value > rule.max) return "OUT_OF_RANGE";
  return undefined;
}

function checkAmount(raw: string, rule: FieldRule, required: boolean): FormErrorCode | undefined {
  if (isBlank(raw)) return required ? "REQUIRED" : undefined;
  const parsed = parseManwon(raw);
  return parsed.ok ? checkRule(rule, parsed.won) : "NOT_NUMBER";
}

function checkCustomName(raw: string, rule: FieldRule): FormErrorCode | undefined {
  if (raw === "") return "REQUIRED";
  const trimmed = raw.trim();
  if (trimmed === "") return "BLANK";
  return Array.from(trimmed).length > rule.max ? "TOO_LONG" : undefined;
}

export function validateForm(form: PlanForm, rules: InputRules): FormErrors {
  const { fields } = rules;
  const errors: FormErrors = {};
  const set = (field: FormField, code: FormErrorCode | undefined) => {
    if (code !== undefined && errors[field] === undefined) errors[field] = code;
  };

  set("goalType", form.goalType === null ? "REQUIRED" : undefined);
  if (form.goalType === "CUSTOM") set("goalCustomName", checkCustomName(form.goalCustomName, fields.goalCustomName));
  set("goalAmount", checkAmount(form.goalAmount, fields.goalAmount, true));
  set("heldAssets", checkAmount(form.heldAssets, fields.heldAssets, true));

  if (!form.periodKnown && !form.savingKnown) {
    set("targetPeriodMonths", "ONE_OF_PERIOD_OR_SAVING");
  }
  if (form.periodKnown) {
    const months = periodMonthsOf(form);
    if (months === null) set("targetPeriodMonths", "REQUIRED");
    else if (!Number.isFinite(months)) set("targetPeriodMonths", "NOT_NUMBER");
    else set("targetPeriodMonths", checkRule(fields.targetPeriodMonths, months));
  }
  if (form.savingKnown) set("monthlySaving", checkAmount(form.monthlySaving, fields.monthlySaving, true));

  if (!isBlank(form.savingsRatePct)) {
    const rate = parseDecimal(form.savingsRatePct);
    set("savingsRatePct", rate.ok ? checkRule(fields.savingsRatePct, rate.value, decimalPlaces(form.savingsRatePct)) : "NOT_NUMBER");
  }

  if (form.existingOn) validateExisting(form, rules, set);
  return errors;
}

function validateExisting(form: PlanForm, rules: InputRules, set: (field: FormField, code: FormErrorCode | undefined) => void) {
  set("existingSavingsMonthly", checkAmount(form.existingSavings, rules.fields.existingSavingsMonthly, false));
  set("existingEtfMonthly", checkAmount(form.existingEtf, rules.fields.existingEtfMonthly, false));
  const savings = parseManwon(form.existingSavings);
  const etf = parseManwon(form.existingEtf);
  const sum = (savings.ok ? savings.won : 0) + (etf.ok ? etf.won : 0);
  if (sum === 0) return;
  const monthly = parseManwon(form.monthlySaving);
  if (form.savingKnown && monthly.ok && sum > monthly.won) {
    set("existingSavingsMonthly", "EXISTING_EXCEEDS_SAVING");
  }
  if (form.ratioChoice === null) set("ratioChoice", "RATIO_CHOICE_REQUIRED");
}
