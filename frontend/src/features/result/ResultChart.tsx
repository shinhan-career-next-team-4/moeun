import { COPY } from "../../shared/copy/copy";
import type { MonthlyPoint, PrincipalPoint, ScenarioCode, ScenarioResult } from "./types";

export interface ResultChartProps {
  scenarios: ScenarioResult[];
  goalAmount: number;
  display: Record<string, string>;
  /** 누적 납입 원금 월별 점(하단 막대 그래프용). 서버 값을 그대로 그린다 */
  principalPoints?: PrincipalPoint[];
  /** 현재 선택된 월 (YYYY-MM) */
  selectedMonth?: string | null;
  /** 월별 포인트 클릭 콜백 (상세 UI는 추후 연결) */
  onSelectMonth?: (month: string, index: number) => void;
}

export const SCENARIO_SERIES: { code: ScenarioCode; color: string; label: string }[] = [
  { code: "GOOD", color: "#dc2626", label: COPY["scenario.GOOD"] },
  { code: "AVG", color: "#16a34a", label: COPY["scenario.AVG"] },
  { code: "BAD", color: "#2563eb", label: COPY["scenario.BAD"] },
];

export const PRINCIPAL_LINE_COLOR = "#000000";
const GOAL_COLOR = "#c2410c";
const AXIS_COLOR = "#5f6b77";
const GUIDE_COLOR = "#64748b";
/** 가로축 눈금 간격(개월). 와이어프레임처럼 1년마다 찍는다. */
const TICK_EVERY_MONTHS = 12;

function pointsOf(scenarios: ScenarioResult[], code: ScenarioCode): MonthlyPoint[] | null {
  const found = scenarios.find((s) => s.scenario === code);
  return found && found.monthlyPoints.length > 0 ? found.monthlyPoints : null;
}

/**
 * 자산 추이 그래프(FR-RES-04, #50, C12a-7).
 *
 * 하단 누적 납입 원금 막대 + 비중에 따른 3개 시나리오 선 그래프(GOOD 빨강, AVG 초록, BAD 파랑)
 * + 목표선 + 월별 클릭 인터랙션.
 */
export function ResultChart({
  scenarios,
  goalAmount,
  display,
  principalPoints,
  selectedMonth = null,
  onSelectMonth,
}: ResultChartProps) {
  const avg = pointsOf(scenarios, "AVG") ?? (scenarios[0]?.monthlyPoints ?? null);
  if (avg === null || avg.length < 2) return null;

  const activeSeries = SCENARIO_SERIES.map((meta) => ({
    ...meta,
    points: pointsOf(scenarios, meta.code),
  })).filter((s): s is { code: ScenarioCode; color: string; label: string; points: MonthlyPoint[] } => s.points !== null);

  const hasPrincipalBars = Array.isArray(principalPoints) && principalPoints.length > 0;

  const allValues = [
    ...scenarios.flatMap((s) => s.monthlyPoints.map((p) => p.afterTaxAsset)),
    ...(hasPrincipalBars ? principalPoints.map((p) => p.principal) : []),
  ];
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
  const stepX = plotW / (count - 1);
  const toX = (index: number) => padLeft + index * stepX;
  const toY = (value: number) => padTop + ((maxValue - value) / range) * plotH;
  const baselineY = toY(Math.max(0, minValue));
  const goalY = toY(goalAmount);

  const hitWidth = Math.max(12, stepX);

  const line = (pts: MonthlyPoint[]) =>
    pts.map((p, i) => `${i === 0 ? "M" : "L"}${toX(i).toFixed(1)},${toY(p.afterTaxAsset).toFixed(1)}`).join(" ");

  const selectedIndex = selectedMonth ? avg.findIndex((p) => p.month === selectedMonth) : -1;

  // 가로축: 양 끝은 display 값을 그대로, 중간 눈금은 서버가 준 월 문자열을 점으로만 바꿔 쓴다.
  const ticks = avg
    .map((point, index) => ({ point, index }))
    .filter(({ index }) => index > 0 && index < count - 1 && index % TICK_EVERY_MONTHS === 0)
    .map(({ point, index }) => ({ x: toX(index), label: point.month.replace("-", ".") }));

  const avgColor = SCENARIO_SERIES.find((s) => s.code === "AVG")?.color ?? "#16a34a";

  return (
    <section aria-label="예상 자산 추이 그래프" className="rounded-xl border border-border bg-surface p-5 shadow-sm">
      <ul className="mb-2 flex flex-wrap justify-end gap-4 text-xs text-text-muted">
        {hasPrincipalBars && (
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="h-3 w-0.5 rounded" style={{ backgroundColor: PRINCIPAL_LINE_COLOR }} />
            {COPY["result.chartLegendPrincipal"]}
          </li>
        )}
        {activeSeries.map((series) => (
          <li key={series.code} className="flex items-center gap-1.5">
            <span aria-hidden className="h-0.5 w-4 rounded" style={{ backgroundColor: series.color }} />
            {series.label}
          </li>
        ))}
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
        {/* 하단 누적 납입 원금 막대 그래프 (검은색 직선) */}
        {hasPrincipalBars &&
          principalPoints.slice(0, count).map((point, i) => {
            const y = toY(point.principal);
            const isSelected = point.month === selectedMonth;
            return (
              <line
                key={`bar-${point.month}`}
                data-series="PRINCIPAL"
                x1={toX(i)}
                y1={baselineY}
                x2={toX(i)}
                y2={y}
                stroke={PRINCIPAL_LINE_COLOR}
                strokeWidth={isSelected ? 3 : 2}
                strokeLinecap="round"
              />
            );
          })}

        {/* 선택된 월 세로 가이드선 */}
        {selectedIndex >= 0 && (
          <line
            x1={toX(selectedIndex)}
            y1={padTop}
            x2={toX(selectedIndex)}
            y2={padTop + plotH}
            stroke={GUIDE_COLOR}
            strokeDasharray="3 3"
            strokeWidth={1.25}
          />
        )}

        {/* 목표선 */}
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

        {/* 3개 시나리오 선 그래프 (BAD -> AVG -> GOOD 순으로 그려 중요 선이 위에 오도록 함) */}
        {[...activeSeries].reverse().map((series) => (
          <path
            key={series.code}
            data-scenario={series.code}
            d={line(series.points)}
            fill="none"
            stroke={series.color}
            strokeWidth={2.25}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ))}

        {/* 선택된 월 포인트 강조 */}
        {selectedIndex >= 0 &&
          activeSeries.map((series) => {
            const pt = series.points[selectedIndex];
            if (!pt) return null;
            return (
              <circle
                key={`dot-${series.code}`}
                cx={toX(selectedIndex)}
                cy={toY(pt.afterTaxAsset)}
                r={4.5}
                fill={series.color}
                stroke="#ffffff"
                strokeWidth={1.5}
              />
            );
          })}

        <text x={padLeft + plotW + 6} y={toY(avg[count - 1].afterTaxAsset) + 4} fontSize="11" fill={avgColor}>
          {display["AVG.afterTaxAsset"]}
        </text>

        {/* 가로축 눈금 */}
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

        {/* 월별 클릭 히트 영역 */}
        {avg.map((point, i) => (
          <rect
            key={`hit-${point.month}`}
            x={toX(i) - hitWidth / 2}
            y={padTop}
            width={hitWidth}
            height={plotH}
            fill="transparent"
            className={onSelectMonth ? "cursor-pointer" : undefined}
            role={onSelectMonth ? "button" : undefined}
            tabIndex={onSelectMonth ? 0 : undefined}
            aria-label={onSelectMonth ? `${point.month} 선택` : undefined}
            onClick={onSelectMonth ? () => onSelectMonth(point.month, i) : undefined}
            onKeyDown={
              onSelectMonth
                ? (e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      onSelectMonth(point.month, i);
                    }
                  }
                : undefined
            }
          />
        ))}
      </svg>
    </section>
  );
}
