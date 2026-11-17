import { UnitInput } from "@/components/ui";
import { copy } from "@/copy/ko";

import { errorText } from "./errorText";
import type { SectionProps } from "./types";

export function RateSection({ form, errors, update }: SectionProps) {
  return (
    <section aria-labelledby="rate-heading" className="flex flex-col gap-3">
      <h2 id="rate-heading" className="text-base font-bold text-text">
        {copy.input.rateSection}
      </h2>
      <UnitInput
        id="savingsRatePct"
        label={copy.input.rate}
        unit="%"
        inputMode="decimal"
        value={form.savingsRatePct}
        onChange={(value) => update({ savingsRatePct: value })}
        error={errorText(errors.savingsRatePct)}
        help={copy.input.rateHelp}
      />
    </section>
  );
}
