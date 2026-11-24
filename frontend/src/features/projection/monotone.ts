/** 화면 좌표의 점. */
export interface XY {
  x: number;
  y: number;
}

const round2 = (n: number) => Number(n.toFixed(2));
const at = (p: XY) => `${round2(p.x)},${round2(p.y)}`;

/**
 * 각 점에서의 곡선 기울기(Fritsch–Butland). 위아래로 튀어 나가지 않고 데이터가 오르다 평평해지는 곳도
 * 자연스럽게 이어 준다(recharts의 monotone 곡선과 같은 종류).
 */
function tangents(points: XY[]): number[] {
  const n = points.length;
  const dx: number[] = [];
  const slope: number[] = [];
  for (let i = 0; i < n - 1; i += 1) {
    dx.push(points[i + 1].x - points[i].x);
    slope.push((points[i + 1].y - points[i].y) / dx[i]);
  }

  const m: number[] = new Array<number>(n);
  m[0] = slope[0];
  m[n - 1] = slope[n - 2];
  for (let i = 1; i < n - 1; i += 1) {
    if (slope[i - 1] * slope[i] <= 0) {
      m[i] = 0; // 방향이 바뀌거나 평평하면 기울기 0
    } else {
      const w1 = 2 * dx[i] + dx[i - 1];
      const w2 = dx[i] + 2 * dx[i - 1];
      m[i] = (w1 + w2) / (w1 / slope[i - 1] + w2 / slope[i]);
    }
  }
  return m;
}

/** 첫 점에서 이어서 나머지 점까지 가는 조각들. 2개면 직선, 3개 이상이면 3차 곡선(C)이다. */
function segments(points: XY[]): string {
  if (points.length === 2) return `L${at(points[1])}`;

  const m = tangents(points);
  let path = "";
  for (let i = 0; i < points.length - 1; i += 1) {
    const [p0, p1] = [points[i], points[i + 1]];
    const h = p1.x - p0.x;
    const c1 = { x: p0.x + h / 3, y: p0.y + (m[i] * h) / 3 };
    const c2 = { x: p1.x - h / 3, y: p1.y - (m[i + 1] * h) / 3 };
    path += `C${at(c1)} ${at(c2)} ${at(p1)}`;
  }
  return path;
}

/** 점들을 부드럽게 잇는 선(SVG path). 점이 2개 미만이면 빈 문자열이다. */
export function monotoneCurve(points: XY[]): string {
  if (points.length < 2) return "";
  return `M${at(points[0])}${segments(points)}`;
}

/**
 * 두 선 사이를 채우는 영역(SVG path). 위쪽 선을 따라 갔다가 아래쪽 선을 거꾸로 돌아와 닫는다.
 * 곡선은 방향을 뒤집어도 같은 모양이라 아래쪽 선은 점 순서만 뒤집어 그린다.
 */
export function areaBetween(upper: XY[], lower: XY[]): string {
  if (upper.length < 2 || lower.length < 2) return "";
  const back = [...lower].reverse();
  return `${monotoneCurve(upper)}L${at(back[0])}${segments(back)}Z`;
}
