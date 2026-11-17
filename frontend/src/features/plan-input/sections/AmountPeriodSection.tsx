import { ChipGroup, FieldError, RadioGroup, UnitInput } from "@/components/ui";
import { copy } from "@/copy/ko";
import { formatPeriod } from "@/lib/format";

import { periodMonthsOf } from "../formState";
import { errorText } from "./errorText";
import type { SectionProps } from "./types";

export function AmountPeriodSection({ form, errors, rules, update }: SectionProps) {
  const periodError = errorText(errors.targetPeriodMonths);
  const currentMonths = periodMonthsOf(form);
  const presetOptions = rules.periodPresets.map((months) => ({ value: String(months), label: formatPeriod(months) }));
  const selectedPreset = presetOptions.find((option) => Number(option.value) === currentMonths)?.value ?? null;

  return (
    <section aria-labelledby="amount-heading" className="flex flex-col gap-4">
      <h2 id="amount-heading" className="text-base font-bold text-text">
        {copy.input.amountSection}
      </h2>
      <div className="grid grid-cols-2 gap-4">
        <UnitInput id="goalAmount" label={copy.input.goalAmount} unit="만원" value={form.goalAmount} onChange={(value) => update({ goalAmount: value })} error={errorText(errors.goalAmount)} required />
        <UnitInput id="heldAssets" label={copy.input.heldAssets} unit="만원" value={form.heldAssets} onChange={(value) => update({ heldAssets: value })} error={errorText(errors.heldAssets)} help={copy.input.heldAssetsHelp} required />
      </div>
      <RadioGroup
        name="periodKnown"
        legend={copy.input.periodKnown}
        options={[
          { value: "yes", label: copy.input.periodYes },
          { value: "no", label: copy.input.periodNo },
        ]}
        value={form.periodKnown ? "yes" : "no"}
        onChange={(value) => update({ periodKnown: value === "yes" })}
      />
      {form.periodKnown && (
        <fieldset className="flex flex-col gap-2" aria-describedby={periodError ? "period-error" : undefined}>
          <legend className="text-sm font-medium text-text">{copy.input.periodLabel}</legend>
          <ChipGroup
            label={copy.input.periodPresetLabel}
            options={presetOptions}
            value={selectedPreset}
            onChange={(value) => {
              const months = Number(value);
              update({ periodYears: String(Math.floor(months / 12)), periodMonths: String(months % 12) });
            }}
          />
          <div className="flex items-center gap-2">
            <PeriodInput id="periodYears" unit={copy.input.periodYears} value={form.periodYears} invalid={Boolean(periodError)} onChange={(value) => update({ periodYears: value })} />
            <PeriodInput id="periodMonths" unit={copy.input.periodMonths} value={form.periodMonths} invalid={Boolean(periodError)} onChange={(value) => update({ periodMonths: value })} />
          </div>
        </fieldset>
      )}
      {periodError && <FieldError id="period-error" message={periodError} />}
    </section>
  );
}

interface PeriodInputProps {
  id: string;
  unit: string;
  value: string;
  invalid: boolean;
  onChange: (value: string) => void;
}

function PeriodInput({ id, unit, value, invalid, onChange }: PeriodInputProps) {
  return (
    <label className={`flex h-11 w-28 items-center rounded-lg border bg-surface px-3 ${invalid ? "border-danger" : "border-border"}`}>
      <input
        id={id}
        aria-label={unit}
        type="text"
        inputMode="numeric"
        value={value}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? "period-error" : undefined}
        onChange={(event) => onChange(event.target.value)}
        className="w-full min-w-0 bg-transparent text-right text-base outline-none"
      />
      <span aria-hidden className="ml-2 text-sm text-text-muted">
        {unit}
      </span>
    </label>
  );
}
