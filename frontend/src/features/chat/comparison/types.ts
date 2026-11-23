import { ACHIEVEMENT_CHANGE_LABELS } from "./copy";

// F-4 응답 모양(계약 C12b-6). 카드가 쓰는 칸만 적고, 형태가 틀리면 parseComparison이 null을 돌려준다.
export interface ComparisonScenario {
  scenario: string;
  achievementChange: string;
}

export interface Comparison {
  /** 불리언 true만 적용 가능으로 본다(P-56). */
  applicable: boolean;
  changedKeys: ReadonlySet<string>;
  display: Readonly<Record<string, string>>;
  scenarios: ComparisonScenario[];
}

export const CONDITION_KEYS = ["monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths"] as const;

/** 목표 금액은 바꿨을 때만 카드 맨 앞 행으로 보인다(D-090). */
export const GOAL_KEY = "goalAmount";

/** 카드 조건 행 순서. 목표 금액을 바꾼 비교면 맨 앞에 목표 금액 행을 둔다. */
export function conditionKeysOf(changedKeys: ReadonlySet<string>): string[] {
  return changedKeys.has(GOAL_KEY) ? [GOAL_KEY, ...CONDITION_KEYS] : [...CONDITION_KEYS];
}

const SCENARIO_CODES: readonly string[] = ["GOOD", "AVG", "BAD"];

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

/** 카드에 필요한 데이터가 없거나 형태가 틀리면 null(P-59). */
export function parseComparison(body: unknown): Comparison | null {
  if (!isRecord(body)) return null;
  const { display, conditions, diff, changedKeys } = body;
  if (!isRecord(display) || !isRecord(conditions) || !isRecord(conditions.current) || !isRecord(conditions.changed)) return null;
  if (!isRecord(diff) || !Array.isArray(diff.byScenario) || diff.byScenario.length === 0) return null;
  // 시나리오 코드는 GOOD·AVG·BAD만 받는다. 비교 양쪽의 시나리오 목록도 마찬가지다(P-65).
  for (const side of [body.current, body.changed]) {
    if (!isRecord(side) || !Array.isArray(side.scenarios)) continue;
    if (!side.scenarios.every((item) => isRecord(item) && typeof item.scenario === "string" && SCENARIO_CODES.includes(item.scenario))) return null;
  }
  const shown = (key: string) => typeof display[key] === "string";
  if (!CONDITION_KEYS.every((key) => shown(`conditions.current.${key}`) && shown(`conditions.changed.${key}`))) return null;
  const scenarios: ComparisonScenario[] = [];
  for (const item of diff.byScenario) {
    if (!isRecord(item) || typeof item.scenario !== "string" || !SCENARIO_CODES.includes(item.scenario) || typeof item.achievementChange !== "string") return null;
    if (!Object.hasOwn(ACHIEVEMENT_CHANGE_LABELS, item.achievementChange)) return null;
    const { scenario } = item;
    if (scenarios.some((seen) => seen.scenario === scenario)) return null; // 같은 시나리오가 두 번 오면 카드 행이 겹친다
    const keys = [`current.${scenario}.afterTaxAsset`, `changed.${scenario}.afterTaxAsset`, `diff.${scenario}.afterTaxAssetDelta`, `changed.${scenario}.gapToGoal`];
    if (!keys.every(shown)) return null;
    scenarios.push({ scenario, achievementChange: item.achievementChange });
  }
  const allowedKeys: readonly string[] = [GOAL_KEY, ...CONDITION_KEYS];
  if (!Array.isArray(changedKeys) || !changedKeys.every((key) => allowedKeys.includes(key))) return null;
  if (changedKeys.includes(GOAL_KEY) && !(shown(`conditions.current.${GOAL_KEY}`) && shown(`conditions.changed.${GOAL_KEY}`))) return null;
  return {
    applicable: body.applicable === true,
    changedKeys: new Set<string>(changedKeys as string[]),
    display: Object.fromEntries(Object.entries(display).map(([key, value]) => [key, String(value)])),
    scenarios,
  };
}
