import { useState } from "react";
import { COPY } from "../../shared/copy/copy";
import { AreaChart } from "./AreaChart";
import { formatMoneyShort } from "./formatMoney";
import { LAYERS } from "./layers";
import { ProjectionUnitToggle } from "./ProjectionUnitToggle";
import { toYearly } from "./selectPoints";
import type { ProjectionData, ProjectionScenario, ProjectionUnit } from "./types";

const SCENARIOS: readonly ProjectionScenario[] = ["GOOD", "AVG", "BAD"];

export interface ProjectionChartProps {
  /** 시나리오 3개의 월별 점(최대 360개월). 모두 서버가 계산한 값이고 이 화면은 그리기만 한다. */
  data: ProjectionData;
  /** 어느 시나리오를 그릴지. 위쪽 3구간 카드가 고르고, 이 화면은 받은 값만 그린다. */
  scenario: ProjectionScenario;
  defaultUnit?: ProjectionUnit;
  /** 계산 시작월("YYYY-MM", `result.calcStartMonth`). 있으면 가로축 눈금이 날짜로 나온다. */
  startMonth?: string;
}

/**
 * 누적 그래프 화면(#298): 범례(항목 옆 최종 금액)와 월별/연별 토글이 한 줄, 그래프, 안내 문구.
 * 시나리오는 바깥(3구간 카드)이 고르고, 월별/연별은 이 화면 안의 상태다. 값은 받은 data에서 점을 고르기만 한다.
 */
export function ProjectionChart({ data, scenario, defaultUnit = "monthly", startMonth }: ProjectionChartProps) {
  const [unit, setUnit] = useState<ProjectionUnit>(defaultUnit);

  const monthly = data[scenario] ?? [];
  if (monthly.length === 0) return null;

  // 범례의 최종 금액은 월별/연별과 상관없이 마지막 달 값이다
  const last = monthly[monthly.length - 1];
  const points = unit === "yearly" ? toYearly(monthly) : monthly;
  const monthRange = { min: monthly[0].month, max: last.month };
  // 세로축은 모든 시나리오 중 가장 큰 합계 기준: 시나리오를 바꿔도 축이 출렁이지 않는다
  const maxValue = Math.max(0, ...SCENARIOS.flatMap((key) => (data[key] ?? []).map((point) => point.total)));

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border bg-surface p-5 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
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
        <ProjectionUnitToggle unit={unit} onChange={setUnit} />
      </div>

      <AreaChart
        points={points}
        monthRange={monthRange}
        maxValue={maxValue}
        revealKey={scenario}
        startMonth={startMonth}
        ariaLabel={COPY["projection.chartLabel"]}
      />

      <p className="text-xs text-text-muted">{COPY["projection.notice"]}</p>
    </div>
  );
}
