/** 눈금 간격 후보의 배수. 10의 거듭제곱에 곱해 1·2·2.5·5로 "깔끔한" 간격을 만든다. */
const STEP_MULTIPLIERS = [1, 2, 2.5, 5];
/** 눈금 칸 수의 상한(칸이 4개면 눈금은 0까지 5개). */
const MAX_INTERVALS = 4;
/** 값이 아주 작거나 0이어도 눈금이 4~5개 나오도록 하는 최소 높이(원). */
const MIN_TOP = 40_000;
/** 가로축 눈금 라벨의 상한. 좁은 화면에서도 겹치지 않게 한다. */
const MAX_YEAR_TICKS = 10;

/**
 * 세로축 눈금(#298). 반드시 0에서 시작하고(축을 잘라 기울기를 과장하지 않는다) 4~5개이며, 마지막 눈금은 max 이상이다.
 * 눈금 값은 "그리는 위치"일 뿐이고 금액을 새로 만드는 것이 아니다.
 */
export function niceTicks(max: number): number[] {
  const top = Math.max(max, MIN_TOP);
  for (let exponent = 0; ; exponent += 1) {
    for (const multiplier of STEP_MULTIPLIERS) {
      const step = multiplier * 10 ** exponent;
      const intervals = Math.ceil(top / step);
      if (intervals <= MAX_INTERVALS) {
        return Array.from({ length: intervals + 1 }, (_, i) => i * step);
      }
    }
  }
}

/**
 * 가로축 눈금이 찍힐 월(#298). 월별 데이터여도 1년, 2년 … 만 찍는다.
 * 기간이 길거나 화면이 좁아 maxTicks개를 못 넣으면(예: 30년, 360px 폭) 간격을 넓혀 라벨이 겹치지 않게 한다.
 */
export function yearTickMonths(totalMonths: number, maxTicks: number = MAX_YEAR_TICKS): number[] {
  const years = Math.floor(totalMonths / 12);
  if (years < 1) return [];

  const stepYears = Math.max(1, Math.ceil(years / Math.min(Math.max(maxTicks, 1), MAX_YEAR_TICKS)));
  const months: number[] = [];
  for (let year = stepYears; year <= years; year += stepYears) {
    months.push(year * 12);
  }
  return months;
}
