import { describe, expect, it } from "vitest";
import { areaBetween, monotoneCurve } from "../monotone";

function numbers(path: string): number[] {
  return [...path.matchAll(/-?\d+(?:\.\d+)?/g)].map((m) => Number(m[0]));
}

describe("부드러운 곡선 monotoneCurve (#298)", () => {
  it("점이 2개 미만이면 빈 경로다", () => {
    expect(monotoneCurve([])).toBe("");
    expect(monotoneCurve([{ x: 0, y: 0 }])).toBe("");
  });

  it("점이 2개면 직선이다", () => {
    expect(monotoneCurve([{ x: 0, y: 10 }, { x: 100, y: 0 }])).toBe("M0,10L100,0");
  });

  it("점이 3개 이상이면 M으로 시작해 C(곡선)로 이어진다", () => {
    const path = monotoneCurve([{ x: 0, y: 0 }, { x: 10, y: 5 }, { x: 20, y: 20 }]);

    expect(path.startsWith("M0,0")).toBe(true);
    expect(path).toContain("C");
  });

  it("값이 한쪽으로만 변하는 구간에서 곡선이 튀어 나가지 않는다(오버슈트 없음)", () => {
    // 평평한 구간이 있는 증가 데이터. 각 조각의 조절점 y는 그 조각 양끝 y 사이에 있어야 한다.
    const pts = [
      { x: 0, y: 100 }, { x: 10, y: 80 }, { x: 20, y: 80 }, { x: 30, y: 30 }, { x: 40, y: 20 },
    ];

    const segments = monotoneCurve(pts).split("C").slice(1).map((s) => numbers(s));

    segments.forEach((seg, i) => {
      const lo = Math.min(pts[i].y, pts[i + 1].y);
      const hi = Math.max(pts[i].y, pts[i + 1].y);
      const [, c1y, , c2y] = seg;
      expect(c1y).toBeGreaterThanOrEqual(lo - 1e-9);
      expect(c1y).toBeLessThanOrEqual(hi + 1e-9);
      expect(c2y).toBeGreaterThanOrEqual(lo - 1e-9);
      expect(c2y).toBeLessThanOrEqual(hi + 1e-9);
    });
  });

  it("뒤집어도 같은 모양이다(양쪽 방향 대칭)", () => {
    const pts = [{ x: 0, y: 50 }, { x: 10, y: 40 }, { x: 20, y: 10 }, { x: 30, y: 5 }];
    const forward = numbers(monotoneCurve(pts));
    const backward = numbers(monotoneCurve([...pts].reverse()));

    // 시작점·끝점이 서로 바뀐다
    expect(forward.slice(0, 2)).toEqual(backward.slice(-2));
  });
});

describe("두 선 사이의 영역 areaBetween (#298)", () => {
  const upper = [{ x: 0, y: 10 }, { x: 10, y: 5 }, { x: 20, y: 0 }];
  const lower = [{ x: 0, y: 100 }, { x: 10, y: 100 }, { x: 20, y: 100 }];

  it("위쪽 선을 따라 갔다가 아래쪽 선을 거꾸로 돌아와 닫는다", () => {
    const path = areaBetween(upper, lower);

    expect(path.startsWith("M0,10")).toBe(true);
    expect(path).toContain("L20,100"); // 아래쪽 선의 끝점에서 이어 붙는다
    expect(path.endsWith("Z")).toBe(true);
  });

  it("점이 2개 미만이면 빈 경로다", () => {
    expect(areaBetween([{ x: 0, y: 1 }], [{ x: 0, y: 2 }])).toBe("");
  });
});
