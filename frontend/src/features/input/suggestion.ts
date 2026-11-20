import { isBlank, manwonToWon, parseNonNegativeInt, splitMonths } from "../../shared/units/units";
import type { PlanForm } from "./types";

/**
 * D-084: 모르는 값을 입력 화면이 대략값으로 계산해 채워 준다.
 *
 * 수익·이자를 반영하지 않고 (목표 금액 − 보유 자산)을 나누기만 하므로 서버가 계산한 실제 필요액보다 크다.
 * 화면은 문구에 '대략'을 밝히고, 사용자는 채워진 값을 고칠 수 있다.
 * 저장·계산·결과 표시는 모두 서버 값을 쓴다(NFR-INT-01의 예외 범위).
 */

/** `rules.yml`의 MONTHLY_SAVING_AMOUNT 상한 1,000만원. */
const MAX_MONTHLY_SAVING_MANWON = 1000;
/** `rules.yml`의 TARGET_PERIOD_MONTHS 상한 360개월(= 30년, TBD-39). */
const MAX_PERIOD_MONTHS = 360;
const WON_PER_MANWON = 10000;

export type SavingSuggestion =
  | { kind: "amount"; manwon: string }
  | { kind: "tooLarge" }
  | { kind: "overGoal" }
  | { kind: "needsInput" };

export type PeriodSuggestion =
  | { kind: "months"; months: number }
  | { kind: "tooLong" }
  | { kind: "overGoal" }
  | { kind: "needsInput" };

/** 남은 금액(원). 입력이 비었거나 형식이 틀리면 null, 이미 목표를 넘었으면 0 이하다. */
function remainingWon(goalAmountManwon: string, heldAssetsManwon: string): number | null {
  if (isBlank(goalAmountManwon) || isBlank(heldAssetsManwon)) return null;
  const goal = manwonToWon(goalAmountManwon);
  const held = manwonToWon(heldAssetsManwon);
  if (!goal.ok || !held.ok) return null;
  return goal.won - held.won;
}

export function suggestMonthlySaving({
  goalAmountManwon,
  heldAssetsManwon,
  periodMonths,
}: {
  goalAmountManwon: string;
  heldAssetsManwon: string;
  periodMonths: number;
}): SavingSuggestion {
  const remaining = remainingWon(goalAmountManwon, heldAssetsManwon);
  if (remaining === null || !Number.isInteger(periodMonths) || periodMonths <= 0) return { kind: "needsInput" };
  if (remaining <= 0) return { kind: "overGoal" };

  const manwon = Math.ceil(remaining / periodMonths / WON_PER_MANWON);
  if (manwon > MAX_MONTHLY_SAVING_MANWON) return { kind: "tooLarge" };
  return { kind: "amount", manwon: String(manwon) };
}

export function suggestPeriodMonths({
  goalAmountManwon,
  heldAssetsManwon,
  monthlySavingManwon,
}: {
  goalAmountManwon: string;
  heldAssetsManwon: string;
  monthlySavingManwon: string;
}): PeriodSuggestion {
  const remaining = remainingWon(goalAmountManwon, heldAssetsManwon);
  if (remaining === null || isBlank(monthlySavingManwon)) return { kind: "needsInput" };
  const monthly = manwonToWon(monthlySavingManwon);
  if (!monthly.ok || monthly.won <= 0) return { kind: "needsInput" };
  if (remaining <= 0) return { kind: "overGoal" };

  const months = Math.ceil(remaining / monthly.won);
  if (months > MAX_PERIOD_MONTHS) return { kind: "tooLong" };
  return { kind: "months", months };
}

/** 년·개월 칸을 개월 수로. 비었거나 형식이 틀리면 0(추천하지 않음). */
export function formPeriodMonths(form: PlanForm): number {
  const years = isBlank(form.periodYears) ? { ok: true as const, value: 0 } : parseNonNegativeInt(form.periodYears);
  const months = isBlank(form.periodMonths) ? { ok: true as const, value: 0 } : parseNonNegativeInt(form.periodMonths);
  if (!years.ok || !months.ok) return 0;
  return years.value * 12 + months.value;
}

/**
 * 추천값을 입힌 폼을 돌려준다(원본은 그대로 둔다). 화면이 보여 주는 값도, 저장하는 값도 이것이다.
 *
 * 한쪽만 모를 때 추천한다. 둘 다 모르면 나눌 근거가 없어 아무것도 채우지 않는다.
 * 사용자가 그 칸을 직접 고쳤으면(`edited`) 추천하지 않는다 — 고친 값이 이긴다.
 */
export function applySuggestions(form: PlanForm, edited: { period: boolean; saving: boolean }): PlanForm {
  if (!form.savingKnown && form.periodKnown && !edited.saving) {
    const suggestion = suggestMonthlySaving({
      goalAmountManwon: form.goalAmount,
      heldAssetsManwon: form.heldAssets,
      periodMonths: formPeriodMonths(form),
    });
    if (suggestion.kind === "amount") return { ...form, monthlySavingAmount: suggestion.manwon };
  }

  if (!form.periodKnown && form.savingKnown && !edited.period) {
    const suggestion = suggestPeriodMonths({
      goalAmountManwon: form.goalAmount,
      heldAssetsManwon: form.heldAssets,
      monthlySavingManwon: form.monthlySavingAmount,
    });
    if (suggestion.kind === "months") {
      const { years, months } = splitMonths(suggestion.months);
      return { ...form, periodYears: String(years), periodMonths: String(months) };
    }
  }

  return form;
}
