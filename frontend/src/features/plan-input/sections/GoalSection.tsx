import { ChipGroup, FieldError, UnitInput } from "@/components/ui";
import { copy } from "@/copy/ko";
import type { GoalType } from "@/lib/api/types";

import { errorText } from "./errorText";
import type { SectionProps } from "./types";

interface GoalSectionProps extends SectionProps {
  onChooseGoal: (goal: GoalType) => void;
}

export function GoalSection({ form, errors, rules, update, onChooseGoal }: GoalSectionProps) {
  const options = rules.goalTypes.map((type) => ({ value: type.code, label: copy.input.goalNames[type.code] }));
  return (
    <section aria-labelledby="goal-heading" className="flex flex-col gap-3">
      <h2 id="goal-heading" className="text-base font-bold text-text">
        {copy.input.goalSection}
      </h2>
      <div id="goalType" tabIndex={-1}>
        <ChipGroup label="목표" options={options} value={form.goalType} onChange={onChooseGoal} />
      </div>
      <p className="text-xs text-text-muted">{copy.input.goalHelp}</p>
      {errors.goalType && <FieldError id="goalType-error" message={errorText(errors.goalType) ?? ""} />}
      {form.goalType === "CUSTOM" && (
        <UnitInput
          id="goalCustomName"
          label={copy.input.customName}
          unit=""
          inputMode={undefined}
          value={form.goalCustomName}
          onChange={(value) => update({ goalCustomName: value })}
          error={errorText(errors.goalCustomName)}
          required
        />
      )}
    </section>
  );
}
