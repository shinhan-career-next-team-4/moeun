import type { GoalType, InputRules, PlanInputDto, PlanView, RatioChoice } from "@/lib/api/types";

import { isBlank, parseCount, parseDecimal, parseManwon } from "./parseManwon";
import { initialMonthlySavingManwon } from "./savingsHelpers";

const WON_PER_MANWON = 10_000;

/** 입력 화면 상태. 금액 문자열은 만원 단위, 사용자가 친 그대로 둔다. */
export interface PlanForm {
  goalType: GoalType | null;
  goalCustomName: string;
  goalAmount: string;
  periodKnown: boolean;
  periodYears: string;
  periodMonths: string;
  heldAssets: string;
  savingKnown: boolean;
  monthlySaving: string;
  monthlySavingTouched: boolean;
  savingsRatePct: string;
  existingOn: boolean;
  existingSavings: string;
  existingEtf: string;
  ratioChoice: RatioChoice | null;
}

export function emptyForm(): PlanForm {
  return {
    goalType: null,
    goalCustomName: "",
    goalAmount: "",
    periodKnown: true,
    periodYears: "",
    periodMonths: "",
    heldAssets: "",
    savingKnown: true,
    monthlySaving: "",
    monthlySavingTouched: false,
    savingsRatePct: "",
    existingOn: false,
    existingSavings: "",
    existingEtf: "",
    ratioChoice: null,
  };
}

function toManwonText(won: number | null): string {
  return won === null ? "" : String(Math.round(won / WON_PER_MANWON));
}

function splitMonths(months: number): { periodYears: string; periodMonths: string } {
  return { periodYears: String(Math.floor(months / 12)), periodMonths: String(months % 12) };
}

/** 입력 수정 진입: 저장된 값을 그대로 채운다. 월 저축액도 자동값으로 덮지 않는다(FR-INP-11) */
export function formFromPlan(plan: PlanView): PlanForm {
  const period = plan.targetPeriodMonths === null ? { periodYears: "", periodMonths: "" } : splitMonths(plan.targetPeriodMonths);
  return {
    goalType: plan.goalType,
    goalCustomName: plan.goalCustomName ?? "",
    goalAmount: toManwonText(plan.goalAmount),
    periodKnown: plan.targetPeriodMonths !== null,
    ...period,
    heldAssets: toManwonText(plan.heldAssets),
    savingKnown: plan.monthlySaving !== null,
    monthlySaving: toManwonText(plan.monthlySaving),
    monthlySavingTouched: true,
    savingsRatePct: plan.savingsRatePct === null ? "" : String(plan.savingsRatePct),
    existingOn: plan.existingSavingsMonthly !== null || plan.existingEtfMonthly !== null,
    existingSavings: toManwonText(plan.existingSavingsMonthly),
    existingEtf: toManwonText(plan.existingEtfMonthly),
    ratioChoice: plan.ratioChoice,
  };
}

/** 목표 칩 선택: 기본값이 있으면 금액·기간을 바꾸고 보유 자산은 유지. 직접 입력은 직전 값 유지 */
export function applyGoalChoice(form: PlanForm, goal: GoalType, rules: InputRules): PlanForm {
  const goalRule = rules.goalTypes.find((type) => type.code === goal);
  if (goalRule?.defaultAmount === undefined || goalRule.defaultPeriodMonths === undefined) {
    return { ...form, goalType: goal };
  }
  return {
    ...form,
    goalType: goal,
    goalAmount: toManwonText(goalRule.defaultAmount),
    ...splitMonths(goalRule.defaultPeriodMonths),
    monthlySavingTouched: false,
  };
}

/** 폼의 기간(개월). 둘 다 비면 null, 숫자가 아니면 NaN */
export function periodMonthsOf(form: PlanForm): number | null {
  if (isBlank(form.periodYears) && isBlank(form.periodMonths)) {
    return null;
  }
  const years = isBlank(form.periodYears) ? { ok: true as const, value: 0 } : parseCount(form.periodYears);
  const months = isBlank(form.periodMonths) ? { ok: true as const, value: 0 } : parseCount(form.periodMonths);
  return years.ok && months.ok ? years.value * 12 + months.value : Number.NaN;
}

/** 사용자가 월 저축액을 직접 고치기 전에는 금액·보유·기간으로 자동 초기값을 채운다 */
export function autoFillMonthlySaving(form: PlanForm, rules: InputRules): PlanForm {
  if (form.monthlySavingTouched || !form.periodKnown || !form.savingKnown) {
    return form;
  }
  const goal = parseManwon(form.goalAmount);
  const held = isBlank(form.heldAssets) ? { ok: true as const, won: 0 } : parseManwon(form.heldAssets);
  const months = periodMonthsOf(form);
  if (!goal.ok || !held.ok || months === null || !Number.isFinite(months) || months <= 0) {
    return form;
  }
  const value = initialMonthlySavingManwon(goal.won / WON_PER_MANWON, held.won / WON_PER_MANWON, months, rules);
  return { ...form, monthlySaving: value === null ? "" : String(value) };
}

function wonOrZero(raw: string): number {
  const parsed = parseManwon(raw);
  return parsed.ok ? parsed.won : 0;
}

/** 검증을 통과한 폼 → 저장 요청. 기존 저축 합계가 0이면 토글 OFF로 보낸다(TBD-13) */
export function toDto(form: PlanForm): PlanInputDto {
  const existingSavings = wonOrZero(form.existingSavings);
  const existingEtf = wonOrZero(form.existingEtf);
  const existingOn = form.existingOn && existingSavings + existingEtf > 0;
  const rate = parseDecimal(form.savingsRatePct);
  const goalType = form.goalType;
  if (goalType === null) {
    throw new Error("검증을 통과한 폼에만 toDto를 쓴다");
  }
  return {
    goalType,
    goalCustomName: goalType === "CUSTOM" ? form.goalCustomName.trim() : null,
    goalAmount: wonOrZero(form.goalAmount),
    targetPeriodMonths: form.periodKnown ? periodMonthsOf(form) : null,
    heldAssets: wonOrZero(form.heldAssets),
    monthlySaving: form.savingKnown ? wonOrZero(form.monthlySaving) : null,
    savingsRatePct: rate.ok ? rate.value : null,
    existingSavingsMonthly: existingOn ? existingSavings : null,
    existingEtfMonthly: existingOn ? existingEtf : null,
    ratioChoice: existingOn ? form.ratioChoice : null,
  };
}
