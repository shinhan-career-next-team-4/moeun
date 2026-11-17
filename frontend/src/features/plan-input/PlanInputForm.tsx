"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { Button } from "@/components/ui";
import { copy } from "@/copy/ko";
import { ApiError } from "@/lib/api/client";
import { getInputRules, getPlan, savePlan } from "@/lib/api/endpoints";
import type { GoalType, InputRules } from "@/lib/api/types";

import { ErrorSummary } from "./ErrorSummary";
import { FIELD_ELEMENT_ID } from "./fieldIds";
import { applyGoalChoice, autoFillMonthlySaving, emptyForm, formFromPlan, toDto, type PlanForm } from "./formState";
import { AmountPeriodSection } from "./sections/AmountPeriodSection";
import { ExistingSavingsSection } from "./sections/ExistingSavingsSection";
import { GoalSection } from "./sections/GoalSection";
import { MonthlySavingSection } from "./sections/MonthlySavingSection";
import { RateSection } from "./sections/RateSection";
import { FIELD_ORDER, validateForm, type FormErrors, type FormField } from "./validateForm";

type LoadState = { status: "loading" } | { status: "ready"; rules: InputRules } | { status: "error" };

/** 입력 화면(SCR-02). 목표를 고르면 아래 섹션이 열리고, 결과 보기에서 검증 후 내 계획으로 저장한다(FR-INP-01~11). */
export function PlanInputForm() {
  const router = useRouter();
  const [load, setLoad] = useState<LoadState>({ status: "loading" });
  const [form, setForm] = useState<PlanForm>(emptyForm);
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    Promise.all([getInputRules(), getPlan()])
      .then(([rules, plan]) => {
        if (plan) setForm(formFromPlan(plan));
        setLoad({ status: "ready", rules });
      })
      .catch(() => setLoad({ status: "error" }));
  }, []);

  if (load.status === "loading") return null;
  if (load.status === "error") {
    return (
      <p role="alert" className="text-sm text-danger">
        {copy.common.networkError}
      </p>
    );
  }
  const { rules } = load;

  function update(patch: Partial<PlanForm>) {
    setForm((current) => autoFillMonthlySaving({ ...current, ...patch }, rules));
  }

  function chooseGoal(goal: GoalType) {
    setForm((current) => autoFillMonthlySaving(applyGoalChoice(current, goal, rules), rules));
  }

  function showErrors(next: FormErrors) {
    setErrors(next);
    const first = FIELD_ORDER.find((field) => next[field] !== undefined);
    if (first) focusField(first);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitError(null);
    const found = validateForm(form, rules);
    if (Object.keys(found).length > 0) {
      showErrors(found);
      return;
    }
    setErrors({});
    setSubmitting(true);
    try {
      await savePlan(toDto(form));
      router.push("/result");
    } catch (error) {
      setSubmitting(false);
      if (error instanceof ApiError && error.status === 400 && error.errors.length > 0) {
        showErrors(serverErrors(error));
      } else if (!(error instanceof ApiError && error.status === 401)) {
        setSubmitError(copy.common.networkError);
      }
    }
  }

  const sectionProps = { form, errors, rules, update };
  return (
    <form onSubmit={handleSubmit} noValidate className="mx-auto flex w-full max-w-2xl flex-col gap-8 rounded-2xl bg-surface p-8 shadow-sm">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-bold text-text">{copy.input.title}</h1>
        <p className="text-sm text-text-muted">{copy.input.lead}</p>
      </header>
      <ErrorSummary count={Object.keys(errors).length} />
      <GoalSection {...sectionProps} onChooseGoal={chooseGoal} />
      {form.goalType !== null && (
        <>
          <AmountPeriodSection {...sectionProps} />
          <MonthlySavingSection {...sectionProps} />
          <RateSection {...sectionProps} />
          <ExistingSavingsSection {...sectionProps} />
        </>
      )}
      {submitError && <p className="text-sm text-danger">{submitError}</p>}
      <div className="flex items-center justify-between">
        <span className="text-xs text-text-muted">* {copy.input.requiredMark}</span>
        <Button type="submit" loading={submitting}>
          {copy.input.submit}
        </Button>
      </div>
    </form>
  );
}

function serverErrors(error: ApiError): FormErrors {
  const known = new Set<string>(FIELD_ORDER);
  const result: FormErrors = {};
  for (const { field, code } of error.errors) {
    if (known.has(field) && result[field as FormField] === undefined) {
      result[field as FormField] = code;
    }
  }
  return result;
}

/** 첫 오류 칸으로 스크롤하고 포커스한다. 칩 그룹은 선택(또는 첫) 칩에 포커스 */
function focusField(field: FormField) {
  requestAnimationFrame(() => {
    const container = document.getElementById(FIELD_ELEMENT_ID[field]);
    const target = container?.matches("input, button") ? container : container?.querySelector<HTMLElement>('[tabindex="0"], input, button');
    target?.scrollIntoView?.({ block: "center" });
    target?.focus();
  });
}
