import { ApiError } from "../../api/errors";
import type { ConditionChanges } from "./types";

/**
 * 비교 영역의 결과를 내 계획에 적용한다(P-6 `POST /api/plan/apply`, #68, 계약 C12a-9).
 * 응답 코드를 화면이 할 일로 바꿔 돌려준다. 문구와 화면 갱신은 부르는 쪽(ResultPage)이 한다.
 */
export interface ApplyComparisonDeps {
  request(method: "POST", path: string, body: unknown): Promise<unknown>;
}

export type ApplyOutcome =
  | { kind: "applied"; revision: number }
  | { kind: "planChanged" }
  | { kind: "noPlan" }
  | { kind: "notApplicable" }
  | { kind: "unauthenticated" }
  | { kind: "failed" };

function revisionOf(body: unknown): number | null {
  if (typeof body !== "object" || body === null) return null;
  const { revision } = body as { revision?: unknown };
  return typeof revision === "number" && Number.isInteger(revision) ? revision : null;
}

function outcomeOf(error: unknown): ApplyOutcome {
  const code = error instanceof ApiError ? error.code : null;
  switch (code) {
    case "PLAN_CHANGED":
      return { kind: "planChanged" };
    case "NO_PLAN":
      return { kind: "noPlan" };
    case "VALIDATION_FAILED":
    case "FIELD_NOT_CHANGEABLE":
      return { kind: "notApplicable" };
    case "UNAUTHENTICATED":
      return { kind: "unauthenticated" };
    default:
      return { kind: "failed" };
  }
}

export async function applyComparison(
  deps: ApplyComparisonDeps,
  { expectedRevision, changes }: { expectedRevision: number; changes: ConditionChanges },
): Promise<ApplyOutcome> {
  try {
    const body = await deps.request("POST", "/api/plan/apply", {
      path: "COMPARE",
      expectedRevision,
      conditionChanges: changes,
    });
    const revision = revisionOf(body);
    // 200이어도 유효한 revision이 없으면 성공이 아니다(대화 적용과 같다)
    return revision === null ? { kind: "failed" } : { kind: "applied", revision };
  } catch (error) {
    return outcomeOf(error);
  }
}
