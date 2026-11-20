// 설명 다시 요청(FR-AI-05, #70). 같은 F-3에 retryOfMessageId를 실어 보내고, 새 설명은 폴링으로 받는다.
import { useCallback, useRef, useState, useSyncExternalStore } from "react";
import { ApiError } from "../../../api/errors";
import type { ChatDeps } from "../index";
import type { ChatMessage, ChatPollingState } from "../types";

/** F-3 요청 함수. F-3 경로는 explanation/ 한 곳에만 두어야 해서(#54) 호출하는 쪽(ChatWindow)이 넘겨 준다. */
type SendExplanation = (calcRunId: number, retryOfMessageId?: number) => Promise<unknown>;

type ChatPolling = Pick<ChatPollingState, "messages" | "hasPending"> & { resume(): void };

/** 다시 요청의 원본: 가장 최근에 끝난 설명(완료된 EXPLANATION이거나 실패한 FAILURE). 서버는 설명이 아닌 행이면 원본 없이 처리한다. */
function lastFinishedExplanation(messages: ChatMessage[]): ChatMessage | undefined {
  return [...messages].reverse().find((m) => m.role === "ASSISTANT" && (m.messageType === "EXPLANATION" || m.messageType === "FAILURE"));
}

export function useRetryExplanation(deps: ChatDeps, polling: ChatPolling, send: SendExplanation) {
  const { planStore: store } = deps;
  const subscribe = useCallback((onChange: () => void) => store.subscribe(() => onChange()), [store]);
  const view = useSyncExternalStore(subscribe, () => store.getState().view);
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false);

  const { messages, hasPending, resume } = polling;
  // 설명이 이미 대기 중이면 보내지 않는다(서버도 409 ANSWER_PENDING으로 막는다).
  const disabled = sending || hasPending;

  const retry = useCallback(() => {
    const calcRunId = view?.calcRunId;
    if (sendingRef.current || disabled || typeof calcRunId !== "number") return;
    sendingRef.current = true;
    setSending(true);
    send(calcRunId, lastFinishedExplanation(messages)?.id).then(
      () => resume(),
      (error: unknown) => {
        // 409: 다른 곳에서 만든 대기 설명이 있다. 오류로 그리지 않고 폴링을 이어 그 설명을 받는다. 그 밖의 오류는 그리지 않는다(F-3과 같다).
        if (error instanceof ApiError && error.code === "ANSWER_PENDING") resume();
      },
    ).finally(() => {
      sendingRef.current = false;
      setSending(false);
    });
  }, [view, disabled, messages, resume, send]);

  return { retry, disabled };
}
