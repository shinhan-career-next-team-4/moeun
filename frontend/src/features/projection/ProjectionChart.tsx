import { useState } from "react";
import { COPY } from "../../shared/copy/copy";
import { ChipGroup, type ChipOption } from "../../shared/ui";
import { AreaChart } from "./AreaChart";
import { formatMoneyShort } from "./formatMoney";
import { LAYERS } from "./layers";
import { formatPeriod } from "./period";
import { toYearly } from "./selectPoints";
import type { ProjectionData, ProjectionScenario, ProjectionUnit } from "./types";

const SCENARIOS: readonly ProjectionScenario[] = ["LOW", "BASE", "HIGH"];

const UNIT_OPTIONS: readonly ChipOption<ProjectionUnit>[] = [
  { value: "monthly", label: COPY["projection.unitMonthly"] },
  { value: "yearly", label: COPY["projection.unitYearly"] },
];

const SCENARIO_OPTIONS: readonly ChipOption<ProjectionScenario>[] = SCENARIOS.map((value) => ({
  value,
  label: COPY[`projection.scenario${value}`],
}));

export interface ProjectionChartProps {
  /** 시나리오 3개의 월별 점. 모두 서버가 계산한 값이고 이 화면은 그리기만 한다. */
  data: ProjectionData;
  defaultScenario?: ProjectionScenario;
  defaultUnit?: ProjectionUnit;
}

/**
 * "N년 뒤 예상 자산" 누적 그래프 화면(#298): 상단 결론 숫자, 범례, 토글 두 개, 그래프, 안내 문구.
 * 토글(월별/연별, 시나리오)은 화면 안의 상태이고, 값은 받은 data에서 고르기만 한다.
 */
export function ProjectionChart({ data, defaultScenario = "BASE", defaultUnit = "monthly" }: ProjectionChartProps) {
  const [scenario, setScenario] = useState<ProjectionScenario>(defaultScenario);
  const [unit, setUnit] = useState<ProjectionUnit>(defaultUnit);

  const monthly = data[scenario];
  if (monthly.length === 0) return null;

  // 결론 숫자와 범례는 월별/연별과 상관없이 마지막 달 값이다
  const last = monthly[monthly.length - 1];
  const points = unit === "yearly" ? toYearly(monthly) : monthly;
  const monthRange = { min: monthly[0].month, max: last.month };
  // 세로축은 모든 시나리오 중 가장 큰 합계 기준: 시나리오를 바꿔도 축이 출렁이지 않는다
  const maxValue = Math.max(0, ...SCENARIOS.flatMap((key) => data[key].map((point) => point.total)));
  const headline = COPY["projection.headline"].replace("{period}", formatPeriod(last.month));

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border bg-surface p-5 shadow-sm">
      <div role="region" aria-label={headline} aria-live="polite" className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <p className="text-sm font-medium text-text-muted">{headline}</p>
        <p className="text-3xl font-bold text-text">{COPY["projection.amount"].replace("{amount}", formatMoneyShort(last.total))}</p>
        <p className="text-lg font-semibold text-success">{COPY["projection.profit"].replace("{amount}", formatMoneyShort(last.profit))}</p>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <ChipGroup label={COPY["projection.unitLabel"]} options={UNIT_OPTIONS} value={unit} onChange={setUnit} />
        <ChipGroup label={COPY["projection.scenarioLabel"]} options={SCENARIO_OPTIONS} value={scenario} onChange={setScenario} />
      </div>

      <ul aria-label={COPY["projection.legendLabel"]} className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
        {LAYERS.map((layer) => (
          <li key={layer.key} className="flex items-center gap-1.5">
            <span aria-hidden className="inline-block size-2.5 rounded-sm" style={{ backgroundColor: layer.color }} />
            {COPY["projection.legendEntry"]
              .replace("{label}", COPY[layer.labelKey])
              .replace("{amount}", formatMoneyShort(last[layer.key]))}
          </li>
        ))}
      </ul>

      <AreaChart
        points={points}
        monthRange={monthRange}
        maxValue={maxValue}
        revealKey={scenario}
        ariaLabel={COPY["projection.chartLabel"]}
      />

      <p className="text-xs text-text-muted">{COPY["projection.notice"]}</p>
    </div>
  );
}
