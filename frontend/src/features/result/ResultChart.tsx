import { COPY } from "../../shared/copy/copy";
import type { MonthlyPoint, ScenarioCode, ScenarioResult } from "./types";

interface ResultChartProps {
  scenarios: ScenarioResult[];
  goalAmount: number;
  display: Record<string, string>;
}

const AVG_COLOR = "#1f5f8b";
const BAND_COLOR = "#aab6ff";
const GOAL_COLOR = "#c2410c";
const AXIS_COLOR = "#5f6b77";
/** 가로축 눈금 간격(개월). 와이어프레임처럼 1년마다 찍는다. */
const TICK_EVERY_MONTHS = 12;

function pointsOf(scenarios: ScenarioResult[], code: ScenarioCode): MonthlyPoint[] | null {
  const found = scenarios.find((s) => s.scenario === code);
  return found && found.monthlyPoints.length > 0 ? found.monthlyPoints : null;
}

/**
 * 자산 추이 그래프(FR-RES-04, #50, C12a-7).
 *
 * 평균 선 + 좋았던 때~나빴던 때 범위 밴드 + 목표선(와이어프레임 W-04).
 * 세로축 눈금 숫자·툴팁·점별 월 라벨은 두지 않는다(C12a-7 R-5).
 */
export function ResultChart({ scenarios, goalAmount, display }: ResultChartProps) {
  const avg = pointsOf(scenarios, "AVG") ?? (scenarios[0]?.monthlyPoints ?? null);
  if (avg === null || avg.length < 2) return null;

  const good = pointsOf(scenarios, "GOOD");
  const bad = pointsOf(scenarios, "BAD");
  // 한쪽이라도 없거나 길이가 다르면 밴드를 그리지 않는다(평균 선과 목표선만).
  const hasBand = good !== null && bad !== null && good.length === avg.length && bad.length === avg.length;

  const allValues = scenarios.flatMap((s) => s.monthlyPoints.map((p) => p.afterTaxAsset));
  const maxValue = Math.max(goalAmount, ...allValues, 1);
  const minValue = Math.min(0, ...allValues);
  const range = Math.max(maxValue - minValue, 1);

  const width = 640;
  const height = 240;
  const padLeft = 16;
  const padRight = 104;
  const padTop = 24;
  const padBottom = 32;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const count = avg.length;
  const toX = (index: number) => padLeft + (index / (count - 1)) * plotW;
  const toY = (value: number) => padTop + ((maxValue - value) / range) * plotH;
  const goalY = toY(goalAmount);

  const line = (pts: MonthlyPoint[]) =>
    pts.map((p, i) => `${i === 0 ? "M" : "L"}${toX(i).toFixed(1)},${toY(p.afterTaxAsset).toFixed(1)}`).join(" ");

  // 밴드: 좋았던 때를 따라 갔다가 나빴던 때를 거꾸로 돌아온다.
  const bandPath =
    hasBand && good && bad
      ? `${line(good)} ${[...bad]
          .reverse()
          .map((point, i) => `L${toX(bad.length - 1 - i).toFixed(1)},${toY(point.afterTaxAsset).toFixed(1)}`)
          .join(" ")} Z`
      : null;

  // 가로축: 양 끝은 display 값을 그대로, 중간 눈금은 서버가 준 월 문자열을 점으로만 바꿔 쓴다.
  const ticks = avg
    .map((point, index) => ({ point, index }))
    .filter(({ index }) => index > 0 && index < count - 1 && index % TICK_EVERY_MONTHS === 0)
    .map(({ point, index }) => ({ x: toX(index), label: point.month.replace("-", ".") }));

  return (
    <section aria-label="예상 자산 추이 그래프" className="rounded-xl border border-border bg-surface p-5 shadow-sm">
      <ul className="mb-2 flex flex-wrap justify-end gap-4 text-xs text-text-muted">
        <li className="flex items-center gap-1.5">
          <span aria-hidden className="h-0.5 w-4 rounded" style={{ backgroundColor: AVG_COLOR }} />
          {COPY["result.chartLegendAvg"]}
        </li>
        {hasBand && (
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="h-2.5 w-4 rounded-sm" style={{ backgroundColor: BAND_COLOR }} />
            {COPY["result.chartLegendBand"]}
          </li>
        )}
        <li className="flex items-center gap-1.5">
          <span
            aria-hidden
            className="h-0 w-4 border-t-2 border-dashed"
            style={{ borderColor: GOAL_COLOR }}
          />
          {COPY["result.chartLegendGoal"]}
        </li>
      </ul>

      <svg viewBox={`0 0 ${width} ${height}`} className="h-auto w-full" role="img" aria-label="예상 자산 추이">
        {bandPath && <path d={bandPath} fill={BAND_COLOR} fillOpacity={0.45} stroke="none" />}

        <line
          x1={padLeft}
          y1={goalY}
          x2={padLeft + plotW}
          y2={goalY}
          stroke={GOAL_COLOR}
          strokeDasharray="4 4"
          strokeWidth={1.5}
        />
        <text x={padLeft + plotW + 6} y={goalY + 4} fontSize="11" fill={GOAL_COLOR}>
          {display["goalAmount"]}
        </text>

        <path d={line(avg)} fill="none" stroke={AVG_COLOR} strokeWidth={2.5} />
        <text x={padLeft + plotW + 6} y={toY(avg[count - 1].afterTaxAsset) + 4} fontSize="11" fill={AVG_COLOR}>
          {display["AVG.afterTaxAsset"]}
        </text>

        <text x={padLeft} y={height - 8} fontSize="11" fill={AXIS_COLOR}>
          {display["calcStartMonth"]}
        </text>
        {ticks.map((tick) => (
          <text key={tick.label} x={tick.x} y={height - 8} textAnchor="middle" fontSize="11" fill={AXIS_COLOR}>
            {tick.label}
          </text>
        ))}
        <text x={padLeft + plotW} y={height - 8} textAnchor="end" fontSize="11" fill={AXIS_COLOR}>
          {display["targetMonth"]}
        </text>
      </svg>
    </section>
  );
}
