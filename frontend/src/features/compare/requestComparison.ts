import { COPY } from "../../shared/copy/copy";
import type { ComparisonView, ConditionChanges } from "./types";

/**
 * 결과 화면에서 조건을 바꿔 비교를 요청한다.
 *
 * 전용 API(#64)는 아직 없다. 목 모드에서는 대화 비교 결과 목(F-4, C12b-6)과 같은 모양을 읽어
 * 화면을 끝까지 확인할 수 있게 하고, 실서버 모드에서는 비교하지 않고 안내만 돌려준다.
 * #64가 생기면 이 함수 안의 경로만 바꾸면 된다(응답 모양은 같다).
 */
const MOCK_COMPARISON_PATH = "/api/chat/messages/1031/comparison";

export interface ComparisonRequestDeps {
  mode: "mock" | "live";
  request(method: string, path: string): Promise<unknown>;
}

export type ComparisonResult = { ok: true; view: ComparisonView } | { ok: false; message: string };

export async function requestComparison(
  deps: ComparisonRequestDeps,
  changes: ConditionChanges,
): Promise<ComparisonResult> {
  if (Object.keys(changes).length === 0) return { ok: false, message: COPY["compare.noChanges"] };
  if (deps.mode !== "mock") return { ok: false, message: COPY["compare.serverPending"] };

  try {
    const body = (await deps.request("GET", MOCK_COMPARISON_PATH)) as ComparisonView;
    return { ok: true, view: body };
  } catch {
    return { ok: false, message: COPY["comparison.loadFailed"] };
  }
}
