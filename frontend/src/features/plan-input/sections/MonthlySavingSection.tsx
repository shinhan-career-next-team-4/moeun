import { RadioGroup, UnitInput } from "@/components/ui";
import { copy } from "@/copy/ko";

import { parseManwon } from "../parseManwon";
import { keepCurrentRatio, splitByRatio } from "../savingsHelpers";
import { errorText } from "./errorText";
import type { SectionProps } from "./types";

const WON_PER_MANWON = 10_000;

/** 월 저축액과 비중대로 나눈 금액 안내. 계산이 아니라 입력값을 비중대로 나눠 보여 주는 것이다 */
export function MonthlySavingSection({ form, errors, rules, update }: SectionProps) {
  return (
    <section aria-labelledby="saving-heading" className="flex flex-col gap-4">
      <h2 id="saving-heading" className="text-base font-bold text-text">
        {copy.input.savingSection}
      </h2>
      <RadioGroup
        name="savingKnown"
        legend={copy.input.savingKnown}
        options={[
          { value: "yes", label: copy.input.savingYes },
          { value: "no", label: copy.input.savingNo },
        ]}
        value={form.savingKnown ? "yes" : "no"}
        onChange={(value) => update({ savingKnown: value === "yes" })}
      />
      {form.savingKnown ? (
        <div className="flex flex-col gap-1">
          <UnitInput
            id="monthlySaving"
            label={copy.input.monthlySaving}
            unit="만원"
            value={form.monthlySaving}
            onChange={(value) => update({ monthlySaving: value, monthlySavingTouched: true })}
            error={errorText(errors.monthlySaving)}
            help={copy.input.monthlySavingHelp}
            required
          />
          <SplitHint form={form} baseRatio={rules.baseSavingsRatioPct} />
        </div>
      ) : (
        <p className="text-sm text-text-muted">{copy.input.savingUnknownHelp}</p>
      )}
    </section>
  );
}

function SplitHint({ form, baseRatio }: { form: SectionProps["form"]; baseRatio: number }) {
  const monthly = parseManwon(form.monthlySaving);
  if (!monthly.ok || monthly.won === 0) {
    return null;
  }
  const savings = parseManwon(form.existingSavings);
  const etf = parseManwon(form.existingEtf);
  const existingSum = (savings.ok ? savings.won : 0) + (etf.ok ? etf.won : 0);
  const keep = form.existingOn && form.ratioChoice === "KEEP_CURRENT" && existingSum > 0;
  const ratio = keep ? keepCurrentRatio(savings.ok ? savings.won : 0, etf.ok ? etf.won : 0) : baseRatio;
  const split = splitByRatio(monthly.won / WON_PER_MANWON, ratio);
  const text = keep ? copy.input.splitKeep : copy.input.split;
  return <p className="text-sm text-primary-strong">{text(`${split.savings}만원`, `${split.etf}만원`)}</p>;
}
