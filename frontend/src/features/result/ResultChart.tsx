import type { ScenarioCode, ScenarioResult } from "./types";

interface ResultChartProps {
  scenarios: ScenarioResult[];
  goalAmount: number;
  display: Record<string, string>;
}

const SCENARIO_COLOR: Record<ScenarioCode, string> = {
  GOOD: "#2e7d32",
  AVG: "#1f5f8b",
  BAD: "#c62828",
};

/** 자산 추이 그래프(FR-RES-04, #50, C12a-7). 세로축 눈금·툴팁·점별 월 라벨은 두지 않는다. */
export function ResultChart({ scenarios, goalAmount, display }: ResultChartProps) {
  if (scenarios.length === 0) return null;
  const maxPoints = Math.max(...scenarios.map((s) => s.monthlyPoints.length));
  if (maxPoints < 2) return null;

  const allValues = scenarios.flatMap((s) => s.monthlyPoints.map((p) => p.afterTaxAsset));
  const maxValue = Math.max(goalAmount, ...allValues, 1);
  const minValue = Math.min(0, ...allValues);
  const range = Math.max(maxValue - minValue, 1);

  const width = 640;
  const height = 240;
  const padLeft = 16;
  const padRight = 104;
  const padTop = 20;
  const padBottom = 32;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const toX = (index: number, count: number) =>
    padLeft + (count <= 1 ? 0 : (index / (count - 1)) * plotW);
  const toY = (value: number) => padTop + ((maxValue - value) / range) * plotH;
  const goalY = toY(goalAmount);

  return (
    <section aria-label="예상 자산 추이 그래프" className="rounded-xl border border-border bg-surface p-5 shadow-sm">
      <svg viewBox={`0 0 ${width} ${height}`} className="h-auto w-full" role="img" aria-label="예상 자산 추이">
        <line
          x1={padLeft}
          y1={goalY}
          x2={padLeft + plotW}
          y2={goalY}
          stroke="#5f6b77"
          strokeDasharray="4 4"
          strokeWidth={1.5}
        />
        <text x={padLeft + plotW + 6} y={goalY + 4} fontSize="11" fill="#5f6b77">
          {display["goalAmount"]}
        </text>

        {scenarios.map((item) => {
          const pts = item.monthlyPoints;
          const d = pts
            .map((p, idx) => `${idx === 0 ? "M" : "L"}${toX(idx, pts.length).toFixed(1)},${toY(p.afterTaxAsset).toFixed(1)}`)
            .join(" ");
          const last = pts[pts.length - 1];
          const lastY = toY(last.afterTaxAsset);
          return (
            <g key={item.scenario}>
              <path d={d} fill="none" stroke={SCENARIO_COLOR[item.scenario]} strokeWidth={2.5} />
              <text x={padLeft + plotW + 6} y={lastY + 4} fontSize="11" fill={SCENARIO_COLOR[item.scenario]}>
                {display[`${item.scenario}.afterTaxAsset`]}
              </text>
            </g>
          );
        })}

        <text x={padLeft} y={height - 8} fontSize="11" fill="#5f6b77">
          {display["calcStartMonth"]}
        </text>
        <text x={padLeft + plotW} y={height - 8} textAnchor="end" fontSize="11" fill="#5f6b77">
          {display["targetMonth"]}
        </text>
      </svg>
    </section>
  );
}
