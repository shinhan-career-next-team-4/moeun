import type { ProjectionPoint } from "./types";

/**
 * 연별 데이터(#298): 첫 점과 12개월마다의 점, 그리고 마지막 점만 **고른다**. 값을 더하거나 고치지 않는다.
 * 마지막 점을 남기는 이유는 3년 4개월처럼 12개월로 나누어떨어지지 않는 기간도 끝까지 그리기 위해서다.
 * 첫 점을 남기는 이유는 월별 그래프와 가로 범위를 같게 해서 토글을 바꿔도 그래프가 옆으로 밀리지 않게 하려는 것이다.
 */
export function toYearly(points: ProjectionPoint[]): ProjectionPoint[] {
  const last = points.length - 1;
  return points.filter((point, index) => index === 0 || index === last || point.month % 12 === 0);
}
