import { Toggle, UnitInput } from "@/components/ui";
import { copy } from "@/copy/ko";
import type { RatioChoice } from "@/lib/api/types";
import { formatRatio } from "@/lib/format";

import { parseManwon } from "../parseManwon";
import { keepCurrentRatio } from "../savingsHelpers";
import { errorText } from "./errorText";
import type { SectionProps } from "./types";

export function ExistingSavingsSection({ form, errors, rules, update }: SectionProps) {
  const savings = parseManwon(form.existingSavings);
  const etf = parseManwon(form.existingEtf);
  const savingsWon = savings.ok ? savings.won : 0;
  const etfWon = etf.ok ? etf.won : 0;
  const showChoice = form.existingOn && savingsWon + etfWon > 0;

  return (
    <section aria-labelledby="existing-heading" className="flex flex-col gap-4">
      <h2 id="existing-heading" className="text-base font-bold text-text">
        {copy.input.existingSection}
      </h2>
      <Toggle label={copy.input.existingToggle} checked={form.existingOn} onChange={(checked) => update({ existingOn: checked })} />
      {form.existingOn && (
        <div className="grid grid-cols-2 gap-4">
          <UnitInput id="existingSavings" label={copy.input.existingSavings} unit="만원" value={form.existingSavings} onChange={(value) => update({ existingSavings: value })} error={errorText(errors.existingSavingsMonthly)} />
          <UnitInput id="existingEtf" label={copy.input.existingEtf} unit="만원" value={form.existingEtf} onChange={(value) => update({ existingEtf: value })} error={errorText(errors.existingEtfMonthly)} />
        </div>
      )}
      {showChoice && (
        <RatioChoiceGroup
          value={form.ratioChoice}
          baseRatio={rules.baseSavingsRatioPct}
          keepRatio={keepCurrentRatio(savingsWon, etfWon)}
          error={errorText(errors.ratioChoice)}
          onChange={(value) => update({ ratioChoice: value })}
        />
      )}
    </section>
  );
}

interface RatioChoiceGroupProps {
  value: RatioChoice | null;
  baseRatio: number;
  keepRatio: number;
  error?: string;
  onChange: (value: RatioChoice) => void;
}

function RatioChoiceGroup({ value, baseRatio, keepRatio, error, onChange }: RatioChoiceGroupProps) {
  const options: { value: RatioChoice; label: string }[] = [
    { value: "BASE", label: copy.input.ratioBase(formatRatio(baseRatio)) },
    { value: "KEEP_CURRENT", label: copy.input.ratioKeep(formatRatio(keepRatio)) },
  ];
  return (
    <fieldset className="flex flex-col gap-2" aria-describedby={error ? "ratioChoice-error" : undefined}>
      <legend className="mb-1 text-sm font-medium text-text">{copy.input.ratioLegend}</legend>
      {options.map((option) => (
        <label key={option.value} className="inline-flex cursor-pointer items-center gap-2 text-sm text-text">
          <input id={`ratioChoice-${option.value}`} type="radio" name="ratioChoice" checked={value === option.value} onChange={() => onChange(option.value)} className="h-4 w-4 accent-primary" />
          {option.label}
        </label>
      ))}
      {error && (
        <p id="ratioChoice-error" className="text-sm text-danger">
          {error}
        </p>
      )}
    </fieldset>
  );
}
