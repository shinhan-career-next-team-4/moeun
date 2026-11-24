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
 * 그래프 카드 안이 아니라 3구간 카드 아래 안내 문구 줄에 놓이므로 따로 떼어 두었다(`ScenarioCards`의 `noticeSlot`).
 */
export function ProjectionUnitToggle({ unit, onChange }: ProjectionUnitToggleProps) {
  return <ChipGroup label={COPY["projection.unitLabel"]} options={UNIT_OPTIONS} value={unit} onChange={onChange} />;
}
