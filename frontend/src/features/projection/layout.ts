/** 이 폭(px) 미만이면 모바일로 보고 그래프를 조금 낮춘다. */
const NARROW_WIDTH = 480;
const HEIGHT_NARROW = 300;
const HEIGHT_WIDE = 420;
/** 이전 길이(비교 데모용). */
const HEIGHT_NARROW_COMPACT = 240;
const HEIGHT_WIDE_COMPACT = 300;

export interface ChartLayout {
  width: number;
  height: number;
  padLeft: number;
  padRight: number;
  padTop: number;
  padBottom: number;
  /** 실제로 그래프가 그려지는 가로·세로 길이. */
  plotW: number;
  plotH: number;
}

/**
 * 그릴 폭(px)에 맞는 여백과 높이. 왼쪽 여백은 "1억 2,000만" 같은 세로축 라벨 자리다.
 * compact를 주면 이전(낮은) 길이로 그린다. 새 길이와 나란히 비교해 보려는 데모용이다.
 */
export function chartLayout(width: number, compact = false): ChartLayout {
  const narrow = width < NARROW_WIDTH;
  const height = compact
    ? narrow ? HEIGHT_NARROW_COMPACT : HEIGHT_WIDE_COMPACT
    : narrow ? HEIGHT_NARROW : HEIGHT_WIDE;
  const padLeft = 64;
  const padRight = 16;
  const padTop = 12;
  const padBottom = 28;
  return { width, height, padLeft, padRight, padTop, padBottom, plotW: width - padLeft - padRight, plotH: height - padTop - padBottom };
}

export interface MonthRange {
  min: number;
  max: number;
}

/** 몇 개월째인지를 가로 좌표(px)로 바꾼다. 월별·연별이 바뀌어도 범위가 같아 그래프가 옆으로 밀리지 않는다. */
export function monthToX(month: number, range: MonthRange, layout: ChartLayout): number {
  const span = Math.max(range.max - range.min, 1);
  return layout.padLeft + ((month - range.min) / span) * layout.plotW;
}

/** 금액을 세로 좌표(px)로 바꾼다. 0이 맨 아래, top(세로축의 맨 위 눈금)이 맨 위다. */
export function valueToY(value: number, top: number, layout: ChartLayout): number {
  return layout.padTop + (1 - value / top) * layout.plotH;
}
