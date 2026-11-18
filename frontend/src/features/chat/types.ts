// F-1·F-2 응답 모양(계약 C12b-3~5). 계약에 없는 필드는 적지 않는다.
export type ChatMessageType =
  | "QUESTION"
  | "ANSWER"
  | "EXPLANATION"
  | "RECALC_RESULT"
  | "CLARIFY"
  | "OUT_OF_SCOPE"
  | "FAILURE";

export interface ChatCitation {
  title: string;
  sourceUrl: string;
  orgName: string;
}

export interface ChatMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  messageType: ChatMessageType | null;
  status: "PENDING" | "COMPLETED" | "FAILED";
  content: string | null;
  citations: ChatCitation[];
  createdAt: string;
}

/** GET /api/chat/messages 응답(F-2). */
export interface ChatPage {
  messages: ChatMessage[];
  hasPending: boolean;
  answerPending: boolean;
}

/** POST /api/chat/messages 202 응답(F-1). */
export interface ChatAccepted {
  userMessageId: number;
  assistantMessageId: number;
}

export interface ChatPollingState {
  messages: ChatMessage[];
  hasPending: boolean;
  answerPending: boolean;
  pollingFailed: boolean;
}
