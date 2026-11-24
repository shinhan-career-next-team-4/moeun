/** 눈금 간격 후보의 배수. 10의 거듭제곱에 곱해 1·2·2.5·5로 "깔끔한" 간격을 만든다. */
const STEP_MULTIPLIERS = [1, 2, 2.5, 5];
/** 눈금 칸 수의 상한(칸이 4개면 눈금은 0까지 5개). */
const MAX_INTERVALS = 4;
/** 값이 아주 작거나 0이어도 눈금이 4~5개 나오도록 하는 최소 높이(원). */
const MIN_TOP = 40_000;
/** 가로축 눈금 라벨의 상한. 좁은 화면에서도 겹치지 않게 한다. */
const MAX_X_TICKS = 12;

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
 * 가입 기간에 따른 가로축 눈금 간격(개월). 12개월 미만 1개월, 12~23개월 2개월, 24~59개월 6개월,
 * 60개월 이상 1년(12개월)이다.
 */
export function tickStepMonths(totalMonths: number): number {
  if (totalMonths < 12) return 1;
  if (totalMonths < 24) return 2;
  if (totalMonths < 60) return 6;
  return 12;
}

/**
 * 가로축 눈금이 찍힐 월. 간격은 {@link tickStepMonths}를 따르고, 자리가 좁아 maxTicks개를 못 넣으면
 * (예: 30년, 360px 폭) 일정한 걸음으로 솎아 라벨이 겹치지 않게 한다. 솎아도 마지막 눈금은 남는다.
 */
export function axisTickMonths(totalMonths: number, maxTicks: number = MAX_X_TICKS): number[] {
  const step = tickStepMonths(totalMonths);
  const all: number[] = [];
  for (let month = step; month <= totalMonths; month += step) {
    all.push(month);
  }
  const limit = Math.min(Math.max(maxTicks, 1), MAX_X_TICKS);
  const stride = Math.max(1, Math.ceil(all.length / limit));
  return all.filter((_, index) => (all.length - 1 - index) % stride === 0);
}
