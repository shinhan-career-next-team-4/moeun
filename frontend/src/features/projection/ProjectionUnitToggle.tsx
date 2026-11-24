import { COPY } from "../../shared/copy/copy";
import { ChipGroup, type ChipOption } from "../../shared/ui";
import type { ProjectionUnit } from "./types";

const UNIT_OPTIONS: readonly ChipOption<ProjectionUnit>[] = [
  { value: "monthly", label: COPY["projection.unitMonthly"] },
  { value: "yearly", label: COPY["projection.unitYearly"] },
];

interface ProjectionUnitToggleProps {
  unit: ProjectionUnit;
  onChange(unit: ProjectionUnit): void;
}

/**
 * 월별 / 연별 토글(#298). 데이터를 몇 개 골라 그릴지만 바꾼다.
 * 그래프 카드 안 범례 줄 오른쪽에 놓인다.
 */
export function ProjectionUnitToggle({ unit, onChange }: ProjectionUnitToggleProps) {
  return <ChipGroup label={COPY["projection.unitLabel"]} options={UNIT_OPTIONS} value={unit} onChange={onChange} />;
}
