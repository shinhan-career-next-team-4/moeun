import { api } from "../../api";
import { ApiError } from "../../api/errors";

/**
 * 직전 계획으로 되돌린다(P-5 `POST /api/plan/undo`, 계약 C12a-8). 되돌리기 토스트와 '직전 계획으로 되돌리기' 버튼(#263)이
 * 같이 쓴다. 응답 코드를 화면이 할 일로 바꿔 돌려주고, 문구와 화면 갱신은 부르는 쪽이 한다.
 */
export type UndoOutcome =
  | { kind: "undone"; revision: number }
  | { kind: "nothingToUndo" }
  | { kind: "planChanged" }
  | { kind: "noPlan" }
  | { kind: "unauthenticated" }
  | { kind: "failed" };

export async function requestUndo(expectedRevision: number): Promise<UndoOutcome> {
  try {
    const res = await api.request<{ revision: number }>("POST", "/api/plan/undo", { expectedRevision });
    return { kind: "undone", revision: res.revision };
  } catch (caught) {
    if (!(caught instanceof ApiError)) return { kind: "failed" };
    switch (caught.code) {
      case "UNAUTHENTICATED":
        return { kind: "unauthenticated" };
      case "NOTHING_TO_UNDO":
        return { kind: "nothingToUndo" };
      case "PLAN_CHANGED":
        return { kind: "planChanged" };
      case "NO_PLAN":
        return { kind: "noPlan" };
      default:
        return { kind: "failed" };
    }
  }
}
