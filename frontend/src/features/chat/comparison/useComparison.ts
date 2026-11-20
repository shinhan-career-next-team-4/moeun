import { useEffect, useMemo, useSyncExternalStore } from "react";
import type { ChatMessage } from "../types";
import { createComparisonController, type ComparisonController, type ComparisonDeps, type ComparisonSnapshot } from "./controller";

export interface ComparisonState {
  snapshot: ComparisonSnapshot;
  controller: ComparisonController;
  /** 지금 가진 메시지 중 가장 큰 RECALC_RESULT id. 새 비교가 도착하면 F-4 응답을 기다리지 않고 바뀐다. */
  latestId: number | null;
  /** 초과 계획이면 적용 버튼을 그리지 않는다. 판단은 ChatWindow가 하고 여기에는 결과만 온다. */
  hideApply: boolean;
}

/** 제어기는 폴링보다 먼저 만들어 폴링이 메시지를 내놓을 때 `receive`를 부르게 한다. */
export function useComparisonController(deps: ComparisonDeps): ComparisonController {
  return useMemo(() => createComparisonController(deps), [deps]);
}

export function useComparison(controller: ComparisonController, messages: ChatMessage[], hideApply: boolean): ComparisonState {
  const snapshot = useSyncExternalStore(controller.listen, controller.getSnapshot);
  useEffect(() => controller.sync(messages), [controller, messages]);

  let latestId: number | null = null;
  for (const message of messages) {
    if (message.messageType === "RECALC_RESULT" && message.status === "COMPLETED" && (latestId === null || message.id > latestId)) {
      latestId = message.id;
    }
  }
  return { snapshot, controller, latestId, hideApply };
}
