import type { ProjectionPoint } from "./types";

/**
 * 연별 데이터(#298): 첫 점과 12개월마다의 점만 **고른다**. 값을 더하거나 고치지 않는다.
 * 첫 점을 남기는 이유는 월별 그래프와 가로 범위를 같게 해서 토글을 바꿔도 그래프가 옆으로 밀리지 않게 하려는 것이다.
 */
export function toYearly(points: ProjectionPoint[]): ProjectionPoint[] {
  return points.filter((point, index) => index === 0 || point.month % 12 === 0);
}
