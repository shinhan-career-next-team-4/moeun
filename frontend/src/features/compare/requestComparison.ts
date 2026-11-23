import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import type { ComparisonView, ConditionChanges } from "./types";

/**
 * 결과 화면에서 조건을 바꿔 비교를 요청한다(FR-CMP-02).
 *
 * 실서버 모드는 조건 변경 비교 API `POST /api/compare`(#64)를 부른다. 응답은 대화 비교 결과(F-4)에서
 * messageId만 뺀 같은 모양이다. 목 모드에는 이 API의 목이 없어 대화 비교 결과 목(F-4, C12b-6)을 읽는다.
 */
const MOCK_COMPARISON_PATH = "/api/chat/messages/1031/comparison";

export interface ComparisonRequestDeps {
  mode: "mock" | "live";
  request(method: "GET" | "POST", path: string, body?: unknown): Promise<unknown>;
}

export type ComparisonResult = { ok: true; view: ComparisonView } | { ok: false; message: string };

function failureMessage(error: unknown): string {
  // 범위 밖·허용 밖 값은 서버가 422로 거절한다. 그 밖의 실패는 다시 시도할 수 있는 실패다
  if (error instanceof ApiError && (error.code === "VALIDATION_FAILED" || error.code === "FIELD_NOT_CHANGEABLE")) {
    return COPY["compare.notApplicable"];
  }
  return COPY["comparison.loadFailed"];
}

export async function requestComparison(
  deps: ComparisonRequestDeps,
  changes: ConditionChanges,
): Promise<ComparisonResult> {
  if (Object.keys(changes).length === 0) return { ok: false, message: COPY["compare.noChanges"] };

  try {
    const body =
      deps.mode === "mock"
        ? await deps.request("GET", MOCK_COMPARISON_PATH)
        : await deps.request("POST", "/api/compare", { conditionChanges: changes });
    return { ok: true, view: body as ComparisonView };
  } catch (error) {
    return { ok: false, message: failureMessage(error) };
  }
}
