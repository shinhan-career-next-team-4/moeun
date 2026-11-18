import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { api, planStore } from "../../api";
import { ApiError } from "../../api/errors";
import type { PlanView } from "../../api/planStore";
import { RESULT_PATH } from "../../app/routes";
import { COPY } from "../../shared/copy/copy";
import { Button, ChipGroup, FieldError, UnitInput } from "../../shared/ui";
import { formatFieldError, goalLabel, type ServerFieldError } from "../../shared/units/units";
import { buildPutPlanBody, emptyForm, FIELD_ORDER, validateClientForm } from "./formState";
import { GOAL_TYPES, type FormErrors, type FormField, type GoalType, type PlanForm } from "./types";

const FIELD_ELEMENT_ID: Record<FormField, string> = {
  goalType: "goalType-group",
  goalAmount: "goalAmount",
  targetPeriodMonths: "periodYears",
  heldAssets: "heldAssets",
  monthlySavingAmount: "monthlySavingAmount",
  savingsRateInputPct: "savingsRateInputPct",
};

const GOAL_OPTIONS = GOAL_TYPES.map((code) => ({ value: code, label: goalLabel(code) }));

export function PlanInputPage() {
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [form, setForm] = useState<PlanForm>(emptyForm);
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;
    planStore
      .loadPlan()
      .then((view) => {
        if (!active) return;
        if (view !== null) {
          navigate(RESULT_PATH, { replace: true });
          return;
        }
        setReady(true);
      })
      .catch((caught) => {
        if (!active) return;
        if (caught instanceof ApiError && caught.code === "UNAUTHENTICATED") return;
        setLoadError(COPY["common.networkError"]);
      });
    return () => {
      active = false;
    };
  }, [navigate]);

  if (loadError) {
    return (
      <div className="mx-auto max-w-2xl px-6 py-10">
        <p role="alert" className="text-sm text-danger">
          {loadError}
        </p>
      </div>
    );
  }
  if (!ready) return null;

  function update(patch: Partial<PlanForm>) {
    setForm((current) => ({ ...current, ...patch }));
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

    const found = validateClientForm(form);
    if (Object.keys(found).length > 0) {
      showErrors(found);
      return;
    }

    setErrors({});
    setSubmitting(true);
    try {
      const view = await api.request<PlanView>(
        "PUT",
        "/api/plan",
        buildPutPlanBody(form, planStore.getState().revision),
      );
      planStore.acceptPlanView(view);
      navigate(RESULT_PATH);
    } catch (caught) {
      setSubmitting(false);
      if (!(caught instanceof ApiError)) {
        setSubmitError(COPY["input.saveFailed"]);
        return;
      }
      if (caught.code === "UNAUTHENTICATED") return;
      if (caught.code === "VALIDATION_FAILED" && caught.problem) {
        showErrors(mapServerErrors(caught.problem.errors as ServerFieldError[]));
      } else if (caught.code === "PLAN_CHANGED") {
        setSubmitError(COPY["plan.changedElsewhere"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
      } else if (caught.kind === "network") {
        setSubmitError(COPY["common.networkError"]);
      } else {
        setSubmitError(COPY["input.saveFailed"]);
      }
    }
  }

  const errorCount = Object.keys(errors).length;

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <form
        onSubmit={handleSubmit}
        noValidate
        className="mx-auto flex w-full max-w-2xl flex-col gap-8 rounded-2xl bg-surface p-8 shadow-sm"
      >
        <header className="flex flex-col gap-1">
          <h1 className="text-xl font-bold text-text">새 목표 세우기</h1>
          <p className="text-sm text-text-muted">목표 금액과 기간, 월 저축 가능 금액을 입력해 주세요.</p>
        </header>

        {errorCount > 0 && (
          <p role="alert" className="rounded-lg bg-surface-muted px-4 py-3 text-sm text-danger">
            입력 항목 {errorCount}개를 확인해 주세요.
          </p>
        )}

        <section className="flex flex-col gap-3">
          <h2 className="text-base font-semibold text-text">
            어떤 목표인가요?
            <span aria-hidden className="ml-0.5 text-danger">
              *
            </span>
          </h2>
          <div id="goalType-group">
            <ChipGroup<GoalType>
              label="목표 종류"
              options={GOAL_OPTIONS}
              value={form.goalType}
              onChange={(goalType) => update({ goalType })}
            />
          </div>
          {errors.goalType && <FieldError id="goalType-error" message={errors.goalType} />}
        </section>

        <section className="grid gap-4 sm:grid-cols-2">
          <UnitInput
            id="goalAmount"
            label="목표 금액"
            unit="만원"
            inputMode="decimal"
            required
            placeholder="예: 2000"
            value={form.goalAmount}
            onChange={(goalAmount) => update({ goalAmount })}
            error={errors.goalAmount}
          />
          <UnitInput
            id="heldAssets"
            label="보유 자산"
            unit="만원"
            inputMode="decimal"
            required
            value={form.heldAssets}
            onChange={(heldAssets) => update({ heldAssets })}
            error={errors.heldAssets}
            help="지금 이 목표에 쓸 수 있는 돈(없으면 0)"
          />
        </section>

        <section className="flex flex-col gap-1.5">
          <span className="text-sm font-medium text-text">
            목표 기간
            <span aria-hidden className="ml-0.5 text-danger">
              *
            </span>
          </span>
          <div className="grid grid-cols-2 gap-3">
            <UnitInput
              id="periodYears"
              label="년"
              unit="년"
              value={form.periodYears}
              placeholder="0"
              onChange={(periodYears) => update({ periodYears })}
              error={errors.targetPeriodMonths ? " " : undefined}
            />
            <UnitInput
              id="periodMonths"
              label="개월"
              unit="개월"
              value={form.periodMonths}
              placeholder="0"
              onChange={(periodMonths) => update({ periodMonths })}
            />
          </div>
          {errors.targetPeriodMonths && <FieldError id="targetPeriodMonths-error" message={errors.targetPeriodMonths} />}
        </section>

        <section className="grid gap-4 sm:grid-cols-2">
          <UnitInput
            id="monthlySavingAmount"
            label="월 저축 가능 금액"
            unit="만원"
            inputMode="decimal"
            required
            placeholder="예: 30"
            value={form.monthlySavingAmount}
            onChange={(monthlySavingAmount) => update({ monthlySavingAmount })}
            error={errors.monthlySavingAmount}
          />
          <UnitInput
            id="savingsRateInputPct"
            label="적금 금리(선택)"
            unit="%"
            inputMode="decimal"
            placeholder="비워 두면 기본 금리"
            value={form.savingsRateInputPct}
            onChange={(savingsRateInputPct) => update({ savingsRateInputPct })}
            error={errors.savingsRateInputPct}
            help="연, 세전 기준"
          />
        </section>

        {submitError && (
          <p role="alert" className="text-sm text-danger">
            {submitError}
          </p>
        )}

        <div className="flex items-center justify-between">
          <span className="text-xs text-text-muted">* 필수 입력 항목</span>
          <Button type="submit" loading={submitting}>
            결과 보기
          </Button>
        </div>
      </form>
    </div>
  );
}

function mapServerErrors(serverErrors: ServerFieldError[]): FormErrors {
  const known = new Set<string>(FIELD_ORDER);
  const result: FormErrors = {};
  for (const err of serverErrors) {
    if (known.has(err.field) && result[err.field as FormField] === undefined) {
      result[err.field as FormField] = formatFieldError(err);
    }
  }
  return result;
}

function focusField(field: FormField) {
  requestAnimationFrame(() => {
    const container = document.getElementById(FIELD_ELEMENT_ID[field]);
    const target = container?.matches("input, button")
      ? container
      : container?.querySelector<HTMLElement>('[tabindex="0"], input, button');
    target?.scrollIntoView?.({ block: "center" });
    target?.focus();
  });
}
