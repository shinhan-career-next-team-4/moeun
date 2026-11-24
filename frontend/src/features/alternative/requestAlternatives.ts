import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import type { ComparisonView, ConditionChanges } from "../compare/types";
import type { AlternativeKey, AlternativeValues, AlternativeValuesByKey } from "./types";

/**
 * 대안 값 API(A-1 `POST /api/alternatives`, #66·#258). 카드 본문은 서버 display 문자열을 그대로 옮기고(D-096),
 * 프론트는 숫자를 만들지 않는다(NFR-INT-01).
 */
export interface AlternativeCardResponse {
  key: AlternativeKey;
  changes: ConditionChanges;
  diff: ComparisonView["diff"] | null;
  display: Record<string, string>;
  /** 허용 범위 안에서 목표를 달성하는 값을 찾지 못했다(#343). 옛 응답에는 없다 */
  unreachable?: boolean;
  /** 바꾼 조건으로 계산한 결과. 기준 시나리오(평균)가 목표를 달성했는지만 쓴다(판정은 서버, #352) */
  changed?: { scenarios?: { scenario: string; isAchieved: boolean }[] } | null;
}

export interface AlternativeResponse {
  hasAlternatives: boolean;
  alternatives: AlternativeCardResponse[];
}

export interface AlternativesDeps {
  request(method: "POST", path: string, body: unknown): Promise<unknown>;
}

export type AlternativesResult =
  | { ok: true; response: AlternativeResponse }
  | { ok: false; kind: "periodInvalid" | "noPlan" | "unauthenticated" | "failed" };

export async function requestAlternatives(deps: AlternativesDeps, targetPeriodMonths?: number): Promise<AlternativesResult> {
  try {
    const body = targetPeriodMonths === undefined ? {} : { targetPeriodMonths };
    const response = (await deps.request("POST", "/api/alternatives", body)) as AlternativeResponse;
    // 모양이 다른 응답은 그리지 않는다(카드 변환에서 깨지지 않게)
    if (!Array.isArray(response?.alternatives)) return { ok: false, kind: "failed" };
    return { ok: true, response };
  } catch (error) {
    const code = error instanceof ApiError ? error.code : null;
    if (code === "VALIDATION_FAILED") return { ok: false, kind: "periodInvalid" };
    if (code === "NO_PLAN") return { ok: false, kind: "noPlan" };
    if (code === "UNAUTHENTICATED") return { ok: false, kind: "unauthenticated" };
    return { ok: false, kind: "failed" };
  }
}

/** 카드 한 장의 본문(A-1-1). 키가 없는 줄은 비운다 */
function toValues(card: AlternativeCardResponse): AlternativeValues | undefined {
  const { key, display, changes, diff } = card;
  const avgAchieved = card.changed?.scenarios?.find((s) => s.scenario === "AVG")?.isAchieved;
  if (card.unreachable) return { unreachable: true, unreachableLimit: display["limit.monthlySavingAmount"] };
  if (Object.keys(changes).length === 0 || diff === null) return undefined;
  const current = display[`conditions.current.${key}`];
  const changed = display[`conditions.changed.${key}`];
  const badAsset = display["changed.BAD.afterTaxAsset"];
  const badGap = display["changed.BAD.gapToGoal"];
  return {
    changedCondition:
      current && changed ? COPY["compare.confirmChange"].replace("{current}", current).replace("{changed}", changed) : undefined,
    change: display["diff.AVG.afterTaxAssetDelta"],
    avgAfterTaxAsset: display["changed.AVG.afterTaxAsset"],
    gapToGoal: display["changed.AVG.gapToGoal"],
    badAfterTaxAsset:
      badAsset && badGap ? COPY["alt.badValue"].replace("{asset}", badAsset).replace("{gap}", badGap) : undefined,
    achieved: avgAchieved === true,
    changes,
    // 확인창(#68)이 같은 모양을 받는다. 대안은 저장된 비교가 아니라 방금 계산한 값이라 늘 적용할 수 있다
    comparison: { applicable: true, changedKeys: [key], display, diff },
  };
}

export function toAlternativeValues(response: AlternativeResponse): AlternativeValuesByKey {
  const values: AlternativeValuesByKey = {};
  for (const card of response.alternatives) {
    const cardValues = toValues(card);
    if (cardValues) values[card.key] = cardValues;
  }
  return values;
}
