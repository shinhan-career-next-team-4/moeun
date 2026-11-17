import { useCallback, useEffect, useRef, useState } from "react";
import type { ChatDeps } from "./index";
import { createPollingController, POLL_INTERVAL_MS, type PollingController } from "./pollingController";
import type { ChatMessage, ChatPollingState } from "./types";

export interface ChatPolling extends ChatPollingState {
  /** 폴링 재개(F-3을 보낸 쪽이 부른다). 실패 횟수·복원 안전장치 사용 표시·pollingFailed를 지운다. */
  resume(): void;
}

/** 화면(ChatWindow)이 쓰는 확장형. F-1 결과를 폴링 상태에 반영하는 함수가 더 있다. */
export interface ChatPollingInternal extends ChatPolling {
  accepted(userMessage: ChatMessage, pendingMessage: ChatMessage): void;
  answerPendingElsewhere(): void;
}

const INITIAL: ChatPollingState = { messages: [], hasPending: false, answerPending: false, pollingFailed: false };

export function useChatPollingInternal(deps: ChatDeps): ChatPollingInternal {
  const [state, setState] = useState<ChatPollingState>(INITIAL);
  const controllerRef = useRef<PollingController | null>(null);

  useEffect(() => {
    const controller = createPollingController(deps, setState);
    controllerRef.current = controller;
    controller.begin(0); // 화면이 열리면 복원 호출을 한 번 보낸다
    return () => {
      controller.dispose();
      controllerRef.current = null;
    };
  }, [deps]);

  const resume = useCallback(() => controllerRef.current?.begin(POLL_INTERVAL_MS), []);
  const accepted = useCallback(
    (userMessage: ChatMessage, pendingMessage: ChatMessage) => controllerRef.current?.accepted(userMessage, pendingMessage),
    [],
  );
  const answerPendingElsewhere = useCallback(() => controllerRef.current?.answerPendingElsewhere(), []);

  return { ...state, resume, accepted, answerPendingElsewhere };
}

export function useChatPolling(deps: ChatDeps): ChatPolling {
  const { messages, hasPending, answerPending, pollingFailed, resume } = useChatPollingInternal(deps);
  return { messages, hasPending, answerPending, pollingFailed, resume };
}
