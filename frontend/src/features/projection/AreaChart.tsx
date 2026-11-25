import { useState, type KeyboardEvent, type PointerEvent } from "react";
import { COPY } from "../../shared/copy/copy";
import { niceTicks, axisTickMonths } from "./axis";
import { formatMoneyShort } from "./formatMoney";
import { LAYERS } from "./layers";
import { chartLayout, monthToX, valueToY, type MonthRange } from "./layout";
import { areaBetween, monotoneCurve, type XY } from "./monotone";
import { formatMonthLabel, formatPeriod } from "./period";
import type { ProjectionPoint } from "./types";
import { useElementWidth } from "./useElementWidth";

/** 폭을 잴 수 없을 때(테스트 등) 쓰는 기본 폭(px). */
const FALLBACK_WIDTH = 640;
const GRID_COLOR = "#e4e6ec";
const AXIS_COLOR = "#787a91";
const GUIDE_COLOR = "#787a91";
/** 가로축 라벨 하나에 필요한 가로 자리(px). 이보다 좁으면 라벨을 솎아 서로 겹치지 않게 한다. */
const X_LABEL_GAP = 36;

export interface AreaChartProps {
  /** 그릴 점(월별이면 매달, 연별이면 골라 낸 점). 모두 서버가 준 값이다. */
  points: ProjectionPoint[];
  /** 가로 범위(개월). 월별·연별이 바뀌어도 같게 해서 그래프가 옆으로 밀리지 않게 한다. */
  monthRange: MonthRange;
  /** 세로축이 덮어야 할 최대 금액. 시나리오를 바꿔도 축이 출렁이지 않게 모든 시나리오 중 가장 큰 합계를 받는다. */
  maxValue: number;
  /** 이 값이 바뀌면 그래프가 왼쪽에서 오른쪽으로 처음부터 다시 차오른다. */
  revealKey: string;
  ariaLabel: string;
  /** 계산 시작월("YYYY-MM"). 있으면 가로축 눈금을 날짜(YYYY.MM)로, 없으면 기간("1년")으로 쓴다. */
  startMonth?: string;
}

/**
 * 누적 영역 그래프(#298). 아래부터 원금 → 적금 이자 → ETF 수익을 쌓고, 위에 올리면 툴팁을 보인다.
 * 값을 계산하지 않고 받은 값을 그리기만 한다. 세로축은 반드시 0부터 시작한다.
 *
 * 겹쳐 놓은 층 세 장: ① 축·격자(가만히 있음) ② 영역(차오르는 애니메이션) ③ 안내선·점(툴팁용)
 */
export function AreaChart({ points, monthRange, maxValue, revealKey, ariaLabel, startMonth }: AreaChartProps) {
  const [wrapRef, width] = useElementWidth<HTMLDivElement>(FALLBACK_WIDTH);
  const [activeIndex, setActiveIndex] = useState<number | null>(null);

  const layout = chartLayout(width);
  const ticks = niceTicks(maxValue);
  const top = ticks[ticks.length - 1];
  const toX = (month: number) => monthToX(month, monthRange, layout);
  const toY = (value: number) => valueToY(value, top, layout);

  // 층의 경계선: 0 → 원금 → 원금+적금 이자 → 원금+적금 이자+ETF 수익. 그리기 위한 누적 좌표일 뿐이다.
  const floor: XY[] = points.map((p) => ({ x: toX(p.month), y: toY(0) }));
  const boundaries: XY[][] = [
    points.map((p) => ({ x: toX(p.month), y: toY(p.principal) })),
    points.map((p) => ({ x: toX(p.month), y: toY(p.principal + p.savingsInterest) })),
    points.map((p) => ({ x: toX(p.month), y: toY(p.principal + p.savingsInterest + p.etfGain) })),
  ];

  const active = activeIndex === null ? null : points[activeIndex];
  const xTicks = axisTickMonths(monthRange.max, Math.floor(layout.plotW / X_LABEL_GAP)).filter((month) => month >= monthRange.min);

  function nearestIndex(clientX: number): number {
    const left = wrapRef.current?.getBoundingClientRect().left ?? 0;
    const x = clientX - left;
    let nearest = 0;
    let best = Number.POSITIVE_INFINITY;
    points.forEach((point, index) => {
      const distance = Math.abs(toX(point.month) - x);
      if (distance < best) {
        best = distance;
        nearest = index;
      }
    });
    return nearest;
  }

  function handlePointer(event: PointerEvent<HTMLDivElement>) {
    if (points.length > 0) setActiveIndex(nearestIndex(event.clientX));
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const last = points.length - 1;
    if (last < 0) return;
    const move: Record<string, (current: number | null) => number | null> = {
      Home: () => 0,
      End: () => last,
      ArrowRight: (i) => (i === null ? 0 : Math.min(i + 1, last)),
      ArrowLeft: (i) => (i === null ? last : Math.max(i - 1, 0)),
      Escape: () => null,
    };
    const next = move[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setActiveIndex(next);
  }

  const svgProps = { width: layout.width, height: layout.height, viewBox: `0 0 ${layout.width} ${layout.height}`, "aria-hidden": true };
  // 툴팁은 점 오른쪽에 띄우고, 화면 오른쪽 절반이면 왼쪽에 띄운다
  const activeX = active === null ? 0 : toX(active.month);
  const tooltipOnLeft = activeX > layout.width / 2;

  return (
    <div
      ref={wrapRef}
      role="group"
      tabIndex={0}
      aria-label={ariaLabel}
      className="relative w-full touch-pan-y rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      style={{ height: layout.height }}
      onPointerMove={handlePointer}
      onPointerDown={handlePointer}
      onPointerLeave={(event) => {
        // 손가락을 뗄 때(touch)는 값을 계속 보여 준다. 마우스는 벗어나면 닫는다
        if (event.pointerType !== "touch") setActiveIndex(null);
      }}
      onKeyDown={handleKeyDown}
      onBlur={() => setActiveIndex(null)}
    >
      {/* ① 축·격자: 가로선만 아주 연하게. 세로 격자선은 없다 */}
      <svg {...svgProps} className="absolute inset-0">
        {ticks.map((tick) => (
          <line
            key={tick}
            data-grid="horizontal"
            x1={layout.padLeft}
            x2={layout.width - layout.padRight}
            y1={toY(tick)}
            y2={toY(tick)}
            stroke={GRID_COLOR}
            strokeWidth={1}
          />
        ))}
        {ticks.map((tick) => (
          <text key={tick} data-axis="y" x={layout.padLeft - 8} y={toY(tick) + 4} textAnchor="end" fontSize="11" fill={AXIS_COLOR}>
            {formatMoneyShort(tick)}
          </text>
        ))}
        {xTicks.map((month) => (
          <text key={month} data-axis="x" x={toX(month)} y={layout.height - 8} textAnchor="middle" fontSize="11" fill={AXIS_COLOR}>
            {(startMonth !== undefined && formatMonthLabel(startMonth, month)) || formatPeriod(month)}
          </text>
        ))}
      </svg>

      {/* ② 영역: 처음과 시나리오가 바뀔 때(revealKey) 왼쪽에서 오른쪽으로 차오른다 */}
      <div key={revealKey} data-reveal className="projection-reveal pointer-events-none absolute inset-0">
        <svg {...svgProps}>
          {LAYERS.map((layer, i) => (
            <path
              key={layer.key}
              data-layer={layer.key}
              d={areaBetween(boundaries[i], i === 0 ? floor : boundaries[i - 1])}
              fill={layer.color}
              fillOpacity={layer.fillOpacity}
              stroke="none"
            />
          ))}
          {LAYERS.map((layer, i) => (
            <path
              key={layer.key}
              data-line={layer.key}
              d={monotoneCurve(boundaries[i])}
              fill="none"
              stroke={layer.color}
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          ))}
        </svg>
      </div>

      {/* ③ 안내선과 점 */}
      {activeIndex !== null && (
        <svg {...svgProps} className="pointer-events-none absolute inset-0">
          <line x1={activeX} x2={activeX} y1={layout.padTop} y2={layout.padTop + layout.plotH} stroke={GUIDE_COLOR} strokeDasharray="3 3" strokeWidth={1.25} />
          {LAYERS.map((layer, i) => (
            <circle key={layer.key} cx={activeX} cy={boundaries[i][activeIndex].y} r={4} fill={layer.color} stroke="#ffffff" strokeWidth={1.5} />
          ))}
        </svg>
      )}

      {active !== null && (
        <div
          role="tooltip"
          className="pointer-events-none absolute z-10 min-w-40 rounded-lg border border-border bg-surface p-3 text-xs shadow-md"
          style={{ top: layout.padTop, ...(tooltipOnLeft ? { right: layout.width - activeX + 12 } : { left: activeX + 12 }) }}
        >
          <p className="mb-1.5 font-semibold text-text">{formatPeriod(active.month)}</p>
          <dl className="flex flex-col gap-1">
            {LAYERS.map((layer) => (
              <div key={layer.key} className="flex items-center justify-between gap-4">
                <dt className="flex items-center gap-1.5 text-text-muted">
                  <span aria-hidden className="inline-block size-2 rounded-full" style={{ backgroundColor: layer.color }} />
                  {COPY[layer.labelKey]}
                </dt>
                <dd className="text-text">{formatMoneyShort(active[layer.key])}</dd>
              </div>
            ))}
            <div className="mt-0.5 flex items-center justify-between gap-4 border-t border-border pt-1.5">
              <dt className="font-semibold text-text">{COPY["projection.total"]}</dt>
              <dd className="font-semibold text-text">{formatMoneyShort(active.total)}</dd>
            </div>
          </dl>
        </div>
      )}
    </div>
  );
}
