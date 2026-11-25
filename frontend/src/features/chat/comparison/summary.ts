// 대화 결과 카드의 요약 규칙(#417). 숫자를 만들지 않고 서버 원값의 같음·다름과 display 문자열만 다룬다(NFR-INT-01).
import { ACHIEVEMENT_CHANGE_LABELS, RESULT_LINE_COPY } from "./copy";
import { CONDITION_KEYS, ETF_KEY, type Direction } from "./types";

/** 조건 행으로 보일 수 있는 조건. 카드 행 순서다. */
const ROW_KEYS: readonly string[] = [...CONDITION_KEYS, ETF_KEY];

/**
 * 비교 기준과 값이 다른 조건만 카드 행 순서로 고른다. 한쪽에 원값이 없으면(예: 목에 종목 코드가 없다)
 * 서버 changedKeys를 따른다.
 */
export function changedConditionKeys(
  baseline: Readonly<Record<string, unknown>>,
  changed: Readonly<Record<string, unknown>>,
  serverChangedKeys: ReadonlySet<string>,
): string[] {
  return ROW_KEYS.filter((key) => {
    const before = baseline[key];
    const after = changed[key];
    if (before === undefined || after === undefined) return serverChangedKeys.has(key);
    return before !== after;
  });
}

const MARK: Record<Direction, string> = { up: "▲", down: "▼", same: "–" };

// 서버 목표 대비 문자열("+약 501만원 달성", "-약 311만원 미달", "0원 달성")에서 금액 부분만 남긴다
const GAP_LABEL = / (달성|미달)$/;
const ABOUT = /^([+-]?)약 /;

/** 시나리오 한 줄: "약 N만원 증가 · 목표보다 +M만원". mark는 색과 함께 보이는 기호, goal은 " · " 뒤의 목표 대비다. */
export function resultLine({
  direction,
  assetDelta,
  gapToGoal,
  achieved,
}: {
  direction: Direction;
  assetDelta: string;
  gapToGoal: string;
  achieved: boolean;
}): { mark: string; text: string; goal: string } {
  const gap = gapToGoal.replace(GAP_LABEL, "").replace(ABOUT, "$1");
  const goal = `${achieved ? RESULT_LINE_COPY.achieved : RESULT_LINE_COPY.missed} ${gap}`;
  return { mark: MARK[direction], text: `${assetDelta} · ${goal}`, goal };
}

/** 바꾼 쪽이 달성인가. 서버 판정(achievementChange)만 쓴다. */
export function isAchievedAfter(achievementChange: string): boolean {
  return achievementChange === "STAYS_ACHIEVED" || achievementChange === "MISS_TO_ACHIEVE";
}

/** 달성 상태가 바뀔 때만 문구가 있다. 계속 달성·계속 미달은 null. */
export function achievementNote(achievementChange: string): string | null {
  if (achievementChange !== "MISS_TO_ACHIEVE" && achievementChange !== "ACHIEVE_TO_MISS") return null;
  return ACHIEVEMENT_CHANGE_LABELS[achievementChange];
}
