import { Fragment, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ChangeEvent } from "react";
import { api, planStore, polling } from "../../api";
import { ApiError } from "../../api/errors";
import { ComparisonNotices, ComparisonResult, useComparison, useComparisonController, type ComparisonPlanStore } from "./comparison";
import { COPY, unitChar } from "./copy";
import { Citations } from "./explanation/Citations";
import { postExplanation, useExplanationRequest } from "./explanation/useExplanationRequest";
import type { ChatDeps } from "./index";
import { useRetryExplanation } from "./retry/useRetryExplanation";
import { Suggestions } from "./suggestions";
import type { ChatAccepted, ChatMessage } from "./types";
import { useChatPollingInternal } from "./useChatPolling";

const MAX_LENGTH = 2000;
// 문구표에 없는 버튼 이름. 문구표에 키가 생기면 옮긴다.
const SEND_LABEL = "보내기";
const RETRY_EXPLANATION_LABEL = "설명 다시 요청";

const defaultDeps: ChatDeps = { api, polling, planStore };

type FieldError = { reason?: unknown; min?: unknown; max?: unknown };

function validationMessage(error: ApiError): string | null {
  const first = (error.problem?.errors[0] ?? {}) as FieldError;
  if (first.reason === "REQUIRED") return COPY.validationRequired;
  if (first.reason === "OUT_OF_RANGE") {
    const min = typeof first.min === "number" ? first.min : 1;
    const max = typeof first.max === "number" ? first.max : MAX_LENGTH;
    return COPY.validationOutOfRange.replace("{min}", unitChar(min)).replace("{max}", unitChar(max));
  }
  return null;
}

function Bubble({ message }: { message: ChatMessage }) {
  if (message.status === "PENDING") {
    return (
      <div data-testid="chat-pending" data-message-id={message.id} data-role={message.role} aria-busy="true">
        …
      </div>
    );
  }
  return (
    <div
      data-testid="chat-message"
      data-message-id={message.id}
      data-role={message.role}
      data-message-type={message.messageType ?? undefined}
      data-status={message.status}
    >
      {message.content}
      {message.status === "COMPLETED" && message.citations.length > 0 && <Citations citations={message.citations} />}
    </div>
  );
}

export function ChatWindow({ deps: givenDeps = defaultDeps }: { deps?: ChatDeps }) {
  // 같은 인스턴스를 담은 새 deps 객체로 다시 그려도 폴링·비교 상태가 초기화되지 않게 인스턴스 기준으로 안정화한다(P-67).
  const deps = useMemo<ChatDeps>(
    () => ({ api: givenDeps.api, polling: givenDeps.polling, planStore: givenDeps.planStore }),
    [givenDeps.api, givenDeps.polling, givenDeps.planStore],
  );
  // ChatDeps.planStore는 develop 기준(읽기·재조회)이다. 적용 반영에 쓰는 acceptRevision·acceptNoPlan은 같은 공유 store 인스턴스가 가진다.
  const comparisonDeps = useMemo(() => ({ api: deps.api, planStore: deps.planStore as ComparisonPlanStore }), [deps]);
  const comparisonController = useComparisonController(comparisonDeps);
  const chat = useChatPollingInternal(deps, comparisonController.receive);
  const { accepted, answerPendingElsewhere } = chat;
  useExplanationRequest(deps, chat);
  const retryExplanation = useRetryExplanation(
    deps,
    chat,
    useCallback((calcRunId: number, retryOfMessageId?: number) => postExplanation(deps.api, calcRunId, retryOfMessageId), [deps.api]),
  );

  // 공유 계획 store는 읽기만 한다. 초과 판단은 settledStatus 하나로 한다.
  const { planStore: store } = deps;
  const subscribe = useCallback((onChange: () => void) => store.subscribe(() => onChange()), [store]);
  const settledStatus = useSyncExternalStore(subscribe, () => store.getState().settledStatus);

  const comparison = useComparison(comparisonController, chat.messages, settledStatus === "ALREADY_EXCEEDED");

  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [inputError, setInputError] = useState<string | null>(null);
  const [toast, setToast] = useState(false);
  const sendingRef = useRef(false);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const trimmed = text.trim();
  // 폴링이 실패로 멈추면 서버 값(answerPending)은 낡은 채로 남는다. 질문을 다시 보내 재개할 수 있어야 한다(서버가 409로 막으면 거기서 처리).
  const answerBlocking = chat.answerPending && !chat.pollingFailed;
  const sendable = trimmed.length >= 1 && trimmed.length <= MAX_LENGTH && !sending && !answerBlocking;

  // example이 있으면 예시 버튼 문장을 그대로 보내고 입력창에 써 둔 글은 지우지 않는다.
  async function send(example?: string): Promise<void> {
    const body = example ?? trimmed;
    if (sendingRef.current || sending || answerBlocking || body.length < 1 || body.length > MAX_LENGTH) return;
    sendingRef.current = true;
    setSending(true);
    setInputError(null);
    setToast(false);
    try {
      const response = await deps.api.request<ChatAccepted>("POST", "/api/chat/messages", { text: body });
      if (!mountedRef.current) return;
      const createdAt = new Date().toISOString();
      accepted(
        { id: response.userMessageId, role: "USER", messageType: "QUESTION", status: "COMPLETED", content: body, citations: [], createdAt },
        { id: response.assistantMessageId, role: "ASSISTANT", messageType: null, status: "PENDING", content: null, citations: [], createdAt },
      );
      if (example === undefined) setText("");
    } catch (error) {
      if (!mountedRef.current) return;
      const code = error instanceof ApiError ? error.code : null;
      if (code === "UNAUTHENTICATED") return; // 로그인 이동은 래퍼가 한다
      if (code === "ANSWER_PENDING") {
        answerPendingElsewhere();
        return;
      }
      const message = code === "VALIDATION_FAILED" && error instanceof ApiError ? validationMessage(error) : null;
      if (message !== null) setInputError(message);
      else setToast(true);
    } finally {
      sendingRef.current = false;
      if (mountedRef.current) setSending(false);
    }
  }

  return (
    <section data-testid="chat-window">
      {settledStatus === "ALREADY_EXCEEDED" && <p data-testid="chat-already-exceeded">{COPY.alreadyExceeded}</p>}
      <div data-testid="chat-messages">
        {chat.messages.map((message) => (
          <Fragment key={message.id}>
            <Bubble message={message} />
            <ComparisonResult comparison={comparison} message={message} />
          </Fragment>
        ))}
      </div>
      <ComparisonNotices comparison={comparison} />
      {chat.pollingFailed && <p data-testid="chat-polling-failed">{COPY.pollingFailed}</p>}
      {settledStatus === "CALCULATED" && (
        <button type="button" data-testid="chat-retry-explanation" disabled={retryExplanation.disabled} onClick={retryExplanation.retry}>
          {RETRY_EXPLANATION_LABEL}
        </button>
      )}
      <Suggestions disabled={sending || answerBlocking} onPick={(question) => void send(question)} />
      <textarea
        data-testid="chat-input"
        maxLength={MAX_LENGTH}
        value={text}
        onChange={(event: ChangeEvent<HTMLTextAreaElement>) => setText(event.target.value)}
      />
      {inputError !== null && <p data-testid="chat-input-error">{inputError}</p>}
      {toast && (
        <p data-testid="chat-toast" role="alert">
          {COPY.sendFailed}
        </p>
      )}
      <button type="button" data-testid="chat-send" disabled={!sendable} onClick={() => void send()}>
        {SEND_LABEL}
      </button>
      <p data-testid="chat-disclaimer">{COPY.disclaimer}</p>
    </section>
  );
}
