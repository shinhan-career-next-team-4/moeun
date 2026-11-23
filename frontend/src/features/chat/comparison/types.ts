import { ACHIEVEMENT_CHANGE_LABELS } from "./copy";

// F-4 응답 모양(계약 C12b-6). 카드가 쓰는 칸만 적고, 형태가 틀리면 parseComparison이 null을 돌려준다.
/** 변화 방향(#284). 값을 새로 만들지 않고 서버 숫자의 크기만 비교해 정한다. */
export type Direction = "up" | "down" | "same";

export interface ComparisonScenario {
  scenario: string;
  achievementChange: string;
  /** 예상 자산(세후)의 변화 방향. diff.byScenario[].afterTaxAssetDelta의 부호다. */
  assetDirection: Direction;
  /** 목표 대비의 변화 방향. 목표 금액이 그대로면 자산 방향과 같다. */
  gapDirection: Direction;
}

export interface Comparison {
  /** 불리언 true만 적용 가능으로 본다(P-56). */
  applicable: boolean;
  changedKeys: ReadonlySet<string>;
  display: Readonly<Record<string, string>>;
  scenarios: ComparisonScenario[];
  /** 한쪽이 이미 목표를 넘은 비교(D-092)면 그 쪽. 이때 scenarios는 비고 카드는 양쪽을 따로 보인다. */
  exceeded: ComparisonSideKey | null;
  /** 시나리오 비교가 없을 때 계산된 쪽에 보일 기준 시나리오. */
  baselineScenario: string;
  /** 숫자 조건 행의 늘림·줄임(바뀐 조건만). 종목은 방향이 없다. */
  conditionDirections: Readonly<Record<string, Direction>>;
}

export type ComparisonSideKey = "current" | "changed";

export const CONDITION_KEYS = ["monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths"] as const;

/** 목표 금액은 바꿨을 때만 카드 맨 앞 행으로 보인다(D-090). */
export const GOAL_KEY = "goalAmount";

/** ETF 종목은 바꿨을 때만 카드 맨 뒤 행으로 보인다(D-097). 값은 conditions.<side>.etfProductName display다. */
export const ETF_KEY = "etfProductCode";
export const ETF_NAME_FIELD = "etfProductName";

/** 카드 조건 행 순서. 목표 금액을 바꾼 비교면 맨 앞에, 종목을 바꾼 비교면 맨 뒤에 그 행을 둔다. */
export function conditionKeysOf(changedKeys: ReadonlySet<string>): string[] {
  return [
    ...(changedKeys.has(GOAL_KEY) ? [GOAL_KEY] : []),
    ...CONDITION_KEYS,
    ...(changedKeys.has(ETF_KEY) ? [ETF_KEY] : []),
  ];
}

/** 조건 행의 display 키. 종목은 코드가 아니라 화면 이름을 보인다. */
export function conditionDisplayKey(side: ComparisonSideKey, key: string): string {
  return `conditions.${side}.${key === ETF_KEY ? ETF_NAME_FIELD : key}`;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function directionOf(before: unknown, after: unknown): Direction | null {
  if (typeof before !== "number" || typeof after !== "number") return null;
  return after > before ? "up" : after < before ? "down" : "same";
}

/** 시나리오의 목표 대비(raw gapToGoal). 없으면 undefined. */
function gapOf(side: unknown, scenario: string): unknown {
  if (!isRecord(side) || !Array.isArray(side.scenarios)) return undefined;
  const found = side.scenarios.find((item) => isRecord(item) && item.scenario === scenario);
  return isRecord(found) ? found.gapToGoal : undefined;
}

const SCENARIO_CODES: readonly string[] = ["GOOD", "AVG", "BAD"];


/** 카드에 필요한 데이터가 없거나 형태가 틀리면 null(P-59). */
export function parseComparison(body: unknown): Comparison | null {
  if (!isRecord(body)) return null;
  const { display, conditions, diff, changedKeys } = body;
  if (!isRecord(display) || !isRecord(conditions) || !isRecord(conditions.current) || !isRecord(conditions.changed)) return null;
  if (!isRecord(diff) || !Array.isArray(diff.byScenario)) return null;
  const sideStatus = (side: unknown) => (isRecord(side) ? side.status : undefined);
  const exceeded: ComparisonSideKey | null =
    sideStatus(body.current) === "ALREADY_EXCEEDED" ? "current" : sideStatus(body.changed) === "ALREADY_EXCEEDED" ? "changed" : null;
  // 시나리오 비교는 양쪽이 다 계산됐을 때만 있다. 한쪽이 초과면 byScenario가 빈 배열이다(D-092)
  if (exceeded === null && diff.byScenario.length === 0) return null;
  const baselineScenario = typeof diff.baselineScenario === "string" ? diff.baselineScenario : "AVG";
  if (exceeded !== null && !SCENARIO_CODES.includes(baselineScenario)) return null;
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
    const assetDirection = directionOf(0, item.afterTaxAssetDelta);
    if (assetDirection === null) return null;
    scenarios.push({ scenario, achievementChange: item.achievementChange, assetDirection, gapDirection: assetDirection });
  }
  const allowedKeys: readonly string[] = [GOAL_KEY, ...CONDITION_KEYS, ETF_KEY];
  if (!Array.isArray(changedKeys) || !changedKeys.every((key) => allowedKeys.includes(key))) return null;
  if (changedKeys.includes(GOAL_KEY) && !(shown(`conditions.current.${GOAL_KEY}`) && shown(`conditions.changed.${GOAL_KEY}`))) return null;
  if (changedKeys.includes(ETF_KEY) && !(shown(conditionDisplayKey("current", ETF_KEY)) && shown(conditionDisplayKey("changed", ETF_KEY)))) return null;
  if (changedKeys.includes(GOAL_KEY)) {
    // 목표가 바뀌면 목표 대비는 자산과 따로 움직인다. 양쪽 raw 목표 대비의 크기로만 방향을 정한다(차이 값은 서버에 없다)
    for (const item of scenarios) {
      item.gapDirection = directionOf(gapOf(body.current, item.scenario), gapOf(body.changed, item.scenario)) ?? item.assetDirection;
    }
  }
  const conditionDirections: Record<string, Direction> = {};
  // 목표 금액 raw는 결과 쪽(current·changed.goalAmount)에도 있다. conditions에 없으면 거기서 읽는다
  const sideConditions = { current: conditions.current, changed: conditions.changed };
  const rawCondition = (side: ComparisonSideKey, key: string): unknown => {
    const value = sideConditions[side][key];
    const result = body[side];
    return value === undefined && key === GOAL_KEY && isRecord(result) ? result.goalAmount : value;
  };
  for (const key of changedKeys as string[]) {
    const direction = directionOf(rawCondition("current", key), rawCondition("changed", key));
    if (direction !== null) conditionDirections[key] = direction;
  }
  if (exceeded !== null) {
    // 초과한 쪽은 보유 자산·목표 금액 안내, 계산된 쪽은 기준 시나리오의 자산·목표 대비를 보인다
    const calculated = exceeded === "current" ? "changed" : "current";
    const keys = [
      `conditions.${exceeded}.heldAssets`,
      `conditions.${exceeded}.${GOAL_KEY}`,
      `${calculated}.${baselineScenario}.afterTaxAsset`,
      `${calculated}.${baselineScenario}.gapToGoal`,
    ];
    if (!keys.every(shown)) return null;
  }
  return {
    applicable: body.applicable === true,
    changedKeys: new Set<string>(changedKeys as string[]),
    display: Object.fromEntries(Object.entries(display).map(([key, value]) => [key, String(value)])),
    scenarios,
    exceeded,
    baselineScenario,
    conditionDirections,
  };
}
