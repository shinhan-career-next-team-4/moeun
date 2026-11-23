import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { api, planStore } from "../../api";
import { ApiError } from "../../api/errors";
import type { PlanView } from "../../api/planStore";
import { RESULT_PATH } from "../../app/routes";
import { COPY } from "../../shared/copy/copy";
import { Button, ChipGroup, FieldError, UnitInput } from "../../shared/ui";
import { formatFieldError, formatUnitValue, goalLabel, isBlank, type ServerFieldError } from "../../shared/units/units";
import { KnownRadio } from "./KnownRadio";
import {
  applySuggestions,
  formPeriodMonths,
  suggestMonthlySaving,
  suggestPeriodMonths,
  type PeriodSuggestion,
  type SavingSuggestion,
} from "./suggestion";
import type { ResultPlanView } from "../result/types";
import { buildPutPlanBody, emptyForm, FIELD_ORDER, formFromPlan, validateClientForm } from "./formState";
import { GOAL_TYPES, type FormErrors, type FormField, type GoalType, type PlanForm } from "./types";

const FIELD_ELEMENT_ID: Record<FormField, string> = {
  goalType: "goalType-group",
  goalAmount: "goalAmount",
  targetPeriodMonths: "periodYears",
  heldAssets: "heldAssets",
  monthlySavingAmount: "monthlySavingAmount",
  savingsRateInputPct: "savingsRateInputPct",
  existingSaving: "existingSavingBank",
};

const GOAL_OPTIONS = GOAL_TYPES.map((code) => ({ value: code, label: goalLabel(code) }));

export function PlanInputPage() {
  const navigate = useNavigate();
  const initialSlot = planStore.getState().view;
  const initialView = initialSlot ? (initialSlot.body as ResultPlanView) : null;

  const [ready, setReady] = useState(initialView !== null);
  const [existingView, setExistingView] = useState<ResultPlanView | null>(initialView);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [form, setForm] = useState<PlanForm>(() => (initialView ? formFromPlan(initialView.plan) : emptyForm()));
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // D-084: 사용자가 추천으로 채워진 칸을 직접 고치면 그 칸은 더 덮어쓰지 않는다.
  const [editedByUser, setEditedByUser] = useState({ period: false, saving: false });

  useEffect(() => {
    if (initialView !== null) return;
    let active = true;
    planStore
      .loadPlan()
      .then((view) => {
        if (!active) return;
        if (view !== null) {
          navigate(RESULT_PATH, { replace: true });
          return;
        }
        setExistingView(null);
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
  }, [initialView, navigate]);

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

  /** 추천으로 채워진 칸을 사용자가 직접 고쳤다. 이제 이 칸은 덮어쓰지 않는다(D-084). */
  function updateByUser(patch: Partial<PlanForm>, field: "period" | "saving") {
    setEditedByUser((current) => ({ ...current, [field]: true }));
    update(patch);
  }

  /** 기간을 아는 경우에만 월 저축액을 추천한다. 둘 다 모르면 채울 근거가 없다(D-084). */
  function currentSavingSuggestion(): SavingSuggestion | null {
    if (form.savingKnown) return null;
    if (!form.periodKnown) return { kind: "needsInput" };
    return suggestMonthlySaving({
      goalAmountManwon: form.goalAmount,
      heldAssetsManwon: form.heldAssets,
      periodMonths: formPeriodMonths(form),
    });
  }

  /** 월 저축액을 아는 경우에만 기간을 추천한다. */
  function currentPeriodSuggestion(): PeriodSuggestion | null {
    if (form.periodKnown) return null;
    if (!form.savingKnown) return { kind: "needsInput" };
    return suggestPeriodMonths({
      goalAmountManwon: form.goalAmount,
      heldAssetsManwon: form.heldAssets,
      monthlySavingManwon: form.monthlySavingAmount,
    });
  }

  /** 라디오를 다시 고르면 추천을 처음부터 받는다. */
  function setKnown(field: "period" | "saving", known: boolean) {
    setEditedByUser((current) => ({ ...current, [field]: false }));
    update(field === "period" ? { periodKnown: known } : { savingKnown: known });
  }

  /** (h) 되보낼 값은 저장을 누르는 시점의 공유 store에서 읽는다. */
  function latestRatioPct(): number | null {
    const slot = planStore.getState().view;
    return slot ? ((slot.body as ResultPlanView).plan.savingsRatioPct ?? null) : null;
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

    const found = validateClientForm(applySuggestions(form, editedByUser));
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
        buildPutPlanBody(applySuggestions(form, editedByUser), planStore.getState().revision, latestRatioPct()),
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

  // D-084: 추천값은 저장하지 않고 렌더 때 파생한다. 사용자가 그 칸을 고치면 그때부터 사용자 값이 이긴다.
  const effective = applySuggestions(form, editedByUser);
  const errorCount = Object.keys(errors).length;
  const recheckNotice = existingView
    ? COPY["input.heldAssetsRecheck"].replace("{calcStartMonth}", existingView.display["calcStartMonth"] ?? "")
    : null;
  const currentRatioText = existingView?.display["conditions.savingsRatioPct"] ?? null;
  const savingSuggestionText = savingNotice(currentSavingSuggestion());
  const periodSuggestionText = periodNotice(currentPeriodSuggestion());

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <form onSubmit={handleSubmit} noValidate className="mx-auto flex w-full max-w-2xl flex-col gap-6">
        <header className="flex flex-col gap-1">
          <h1 className="text-xl font-bold text-text">
            {existingView ? "입력 수정" : "어떤 목돈을 모으고 싶으세요?"}
          </h1>
          <p className="text-sm text-text-muted">목표를 먼저 정하면, 필요한 정보만 이어서 물어볼게요.</p>
        </header>

        {recheckNotice && (
          <p className="rounded-lg bg-primary-soft px-4 py-3 text-sm text-primary-strong">{recheckNotice}</p>
        )}

        {errorCount > 0 && (
          <p role="alert" className="rounded-lg bg-surface-muted px-4 py-3 text-sm text-danger">
            입력 항목 {errorCount}개를 확인해 주세요.
          </p>
        )}

        <FormCard step={1} title="목표">
          <div id="goalType-group">
            <ChipGroup<GoalType>
              label="목표 종류"
              options={GOAL_OPTIONS}
              value={form.goalType}
              onChange={(goalType) => update({ goalType })}
            />
          </div>
          {errors.goalType ? (
            <FieldError id="goalType-error" message={errors.goalType} />
          ) : (
            <p className="text-xs text-text-muted">목표는 1개만 선택할 수 있어요</p>
          )}
        </FormCard>

        <FormCard step={2} title="목표 금액과 기간">
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
            help="필수 · 1만 원 ~ 100억 원"
          />

          <KnownRadio
            name="periodKnown"
            label={COPY["input.periodKnownLabel"]}
            yesLabel={COPY["input.periodKnownYes"]}
            noLabel={COPY["input.periodKnownNo"]}
            value={form.periodKnown}
            onChange={(known) => setKnown("period", known)}
          />

          <div className="flex flex-col gap-1.5">
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
                value={effective.periodYears}
                placeholder="0"
                onChange={(periodYears) => updateByUser({ periodYears }, "period")}
                error={errors.targetPeriodMonths ? " " : undefined}
              />
              <UnitInput
                id="periodMonths"
                label="개월"
                unit="개월"
                value={effective.periodMonths}
                placeholder="0"
                onChange={(periodMonths) => updateByUser({ periodMonths }, "period")}
              />
            </div>
            {errors.targetPeriodMonths ? (
              <FieldError id="targetPeriodMonths-error" message={errors.targetPeriodMonths} />
            ) : (
              <p className="text-xs text-text-muted">필수 · 1개월 ~ 30년</p>
            )}
            {periodSuggestionText && (
              <p className="rounded-lg bg-primary-soft px-3 py-2 text-xs text-primary-strong">{periodSuggestionText}</p>
            )}
          </div>

          <UnitInput
            id="heldAssets"
            label="지금 이 목표에 쓸 수 있는 돈"
            unit="만원"
            inputMode="decimal"
            required
            value={form.heldAssets}
            onChange={(heldAssets) => update({ heldAssets })}
            error={errors.heldAssets}
            help="필수 · 0원 ~ 100억 원. '이미 목표를 넘었어요' 안내에 사용해요"
          />
        </FormCard>

        <FormCard step={3} title="매달 저축할 수 있는 금액">
          <KnownRadio
            name="savingKnown"
            label={COPY["input.savingKnownLabel"]}
            yesLabel={COPY["input.savingKnownYes"]}
            noLabel={COPY["input.savingKnownNo"]}
            value={form.savingKnown}
            onChange={(known) => setKnown("saving", known)}
          />
          <UnitInput
            id="monthlySavingAmount"
            label="월 저축 가능 금액"
            unit="만원"
            inputMode="decimal"
            required
            placeholder="예: 30"
            value={effective.monthlySavingAmount}
            onChange={(monthlySavingAmount) => updateByUser({ monthlySavingAmount }, "saving")}
            error={errors.monthlySavingAmount}
            help="필수 · 1만 원 ~ 1,000만 원"
          />
          {savingSuggestionText && (
            <p className="rounded-lg bg-primary-soft px-3 py-2 text-xs text-primary-strong">{savingSuggestionText}</p>
          )}
        </FormCard>

        <FormCard step={4} title="적금 금리">
          <UnitInput
            id="savingsRateInputPct"
            label="적금 금리(선택)"
            unit="%"
            inputMode="decimal"
            placeholder="비워 두면 기본 금리"
            value={form.savingsRateInputPct}
            onChange={(savingsRateInputPct) => update({ savingsRateInputPct })}
            error={errors.savingsRateInputPct}
            help="연·세전 기준. 우대금리를 받을 수 있다면 직접 입력하세요 · 0~20%, 소수 둘째 자리까지"
          />
        </FormCard>

        <FormCard step={5} title="지금 하고 있는 저축">
          <label className="flex items-center gap-2 text-sm font-medium text-text">
            <input
              id="existingSavingOn"
              type="checkbox"
              className="h-4 w-4"
              checked={form.existingSavingOn}
              onChange={(event) => update({ existingSavingOn: event.target.checked })}
            />
            {COPY["input.existingSavingToggle"]}
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <UnitInput
              id="existingSavingBank"
              label={COPY["input.existingSavingBank"]}
              unit="만원"
              inputMode="decimal"
              value={form.existingSavingBank}
              onChange={(existingSavingBank) => update({ existingSavingBank })}
              disabled={!form.existingSavingOn}
            />
            <UnitInput
              id="existingSavingEtf"
              label={COPY["input.existingSavingEtf"]}
              unit="만원"
              inputMode="decimal"
              value={form.existingSavingEtf}
              onChange={(existingSavingEtf) => update({ existingSavingEtf })}
              disabled={!form.existingSavingOn}
            />
          </div>
          {errors.existingSaving && <FieldError id="existingSaving-error" message={errors.existingSaving} />}
          <p className="text-xs text-text-muted">{COPY["input.existingSavingHelp"]}</p>
          {form.existingSavingOn && isBlank(form.existingSavingBank) && isBlank(form.existingSavingEtf) && (
            <p className="text-xs text-text-muted">{COPY["input.ratioKept"]}</p>
          )}
        </FormCard>

        {currentRatioText && (
          <p className="rounded-lg bg-surface-muted px-4 py-3 text-sm text-text-muted">
            {COPY["input.currentRatioLabel"]}: <strong className="text-text">{currentRatioText}</strong>
          </p>
        )}

        {submitError && (
          <p role="alert" className="text-sm text-danger">
            {submitError}
          </p>
        )}

        <div className="flex items-center justify-between">
          <span className="text-xs text-text-muted">* 필수 입력 항목</span>
          <Button type="submit" loading={submitting} disabled={!ready}>
            결과 보기
          </Button>
        </div>
      </form>
    </div>
  );
}

/** 추천 결과를 화면 문구로. 채워 줄 수 없는 경우에는 왜인지 알려 준다(D-084). */
function savingNotice(suggestion: SavingSuggestion | null): string | null {
  if (suggestion === null) return null;
  if (suggestion.kind === "amount") {
    return COPY["input.suggestedMonthlySaving"].replace("{amount}", `${suggestion.manwon}만원`);
  }
  if (suggestion.kind === "tooLarge") return COPY["input.suggestSavingTooLarge"];
  if (suggestion.kind === "needsInput") return COPY["input.suggestNeedsMoreInput"];
  return null; // overGoal은 결과 화면의 초과 안내(FR-RES-01)에 맡긴다
}

function periodNotice(suggestion: PeriodSuggestion | null): string | null {
  if (suggestion === null) return null;
  if (suggestion.kind === "months") {
    return COPY["input.suggestedPeriod"].replace("{period}", formatUnitValue(suggestion.months, "MONTH"));
  }
  if (suggestion.kind === "tooLong") return COPY["input.suggestPeriodTooLong"];
  if (suggestion.kind === "needsInput") return COPY["input.suggestNeedsMoreInput"];
  return null;
}

function FormCard({ step, title, children }: { step: number; title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-2xl bg-surface p-6 shadow-sm">
      <h2 className="text-base font-semibold text-text">
        {step}. {title}
      </h2>
      {children}
    </section>
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
