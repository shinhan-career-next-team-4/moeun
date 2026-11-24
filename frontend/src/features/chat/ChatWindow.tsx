import {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ChangeEvent,
  type ReactNode,
} from "react";
import { api, planStore, polling } from "../../api";
import { ApiError } from "../../api/errors";
import { ComparisonNotices, ComparisonResult, useComparison, useComparisonController, type ComparisonPlanStore } from "./comparison";
import { COPY, unitChar } from "./copy";
import { Citations, EtfRiskCitations } from "./explanation/Citations";
import { planHasNoEtf } from "./planEtf";
import { planGoalName } from "./planGoal";
import { postExplanation, useExplanationRequest } from "./explanation/useExplanationRequest";
import type { ChatDeps } from "./index";
import { useRetryExplanation } from "./retry/useRetryExplanation";
import { EtfChips, planEtfProductCode, Suggestions, useEtfProducts } from "./suggestions";
import type { ChatAccepted, ChatMessage } from "./types";
import { useChatPollingInternal } from "./useChatPolling";

const MAX_LENGTH = 2000;
// 사용자가 맨 아래에서 이만큼(px) 안쪽에 있으면 "읽는 중이 아님"으로 보고 새 메시지에 따라 내려간다.
const STICK_TO_BOTTOM_PX = 48;

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

function TypingIndicator() {
  return (
    <span className="inline-flex items-center gap-1" role="status" aria-label={COPY.typing}>
      {[0, 150, 300].map((delay) => (
        <span
          key={delay}
          aria-hidden="true"
          className="size-1.5 animate-pulse rounded-full bg-text-muted"
          style={{ animationDelay: `${delay}ms` }}
        />
      ))}
    </span>
  );
}

const BUBBLE_BASE = "max-w-[85%] whitespace-pre-wrap break-words rounded-2xl px-4 py-3 text-sm leading-relaxed";
const BUBBLE_ASSISTANT = `${BUBBLE_BASE} self-start bg-surface-muted text-text`;
const BUBBLE_USER = `${BUBBLE_BASE} self-end bg-primary text-white`;

interface BubbleProps {
  message: ChatMessage;
  /** 실패한 설명 아래에 [다시 시도]를 그릴 때만 준다. "설명 다시 요청"과 같은 동작이다. */
  onRetry?: () => void;
  retryDisabled?: boolean;
  /** 지금 계획이 ETF 0%인지. 서버가 그 설명의 ETF 포함 여부를 주지 않았을 때만 쓴다(#283, #305). */
  planHasNoEtf?: boolean;
}

function BubbleCitations({ message, planHasNoEtf }: { message: ChatMessage; planHasNoEtf: boolean }) {
  if (message.status !== "COMPLETED" || message.citations.length === 0) return null;
  // 질문 답·비교 답의 출처는 그대로 보이고, 결과 설명의 근거만 ETF 위험 설명으로 접는다.
  if (message.messageType !== "EXPLANATION") return <Citations citations={message.citations} />;
  // 설명이 나온 계산 기준(#305). 그 값이 없는 응답이면 지금 계획 기준이다
  const etfIncluded = message.etfIncluded ?? !planHasNoEtf;
  return etfIncluded ? <EtfRiskCitations citations={message.citations} /> : null;
}

function Bubble({ message, onRetry, retryDisabled, planHasNoEtf = false }: BubbleProps) {
  const className = message.role === "USER" ? BUBBLE_USER : BUBBLE_ASSISTANT;
  if (message.status === "PENDING") {
    return (
      <div
        data-testid="chat-pending"
        data-message-id={message.id}
        data-role={message.role}
        aria-busy="true"
        className={className}
      >
        <TypingIndicator />
      </div>
    );
  }
  return (
    <>
      <div
        data-testid="chat-message"
        data-message-id={message.id}
        data-role={message.role}
        data-message-type={message.messageType ?? undefined}
        data-status={message.status}
        className={className}
      >
        {message.content}
        <BubbleCitations message={message} planHasNoEtf={planHasNoEtf} />
      </div>
      {onRetry && (
        <button
          type="button"
          data-testid="chat-retry-failure"
          disabled={retryDisabled}
          onClick={onRetry}
          className="self-start rounded-lg border border-border bg-surface px-3 py-1.5 text-xs font-semibold text-primary hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted"
        >
          {COPY.retryFailed}
        </button>
      )}
    </>
  );
}

interface ChatWindowProps {
  deps?: ChatDeps;
  /** 패널 머리글 오른쪽에 붙는 단추(접기·닫기). 배치는 부르는 쪽이 정한다. */
  headerAction?: ReactNode;
  /** 제목 바로 옆에 붙는 단추(예: 패널 위치 전환, #362). */
  titleAction?: ReactNode;
  /** 대기 중이던 어시스턴트 메시지가 끝날 때마다 부른다(서랍이 닫혀 있을 때 점 표시용). 복원된 지난 대화는 부르지 않는다. */
  onAssistantMessage?: () => void;
  /** 패널이 보이는 중인지. 숨겨진 동안은 스크롤이 먹지 않아서, 다시 보이는 순간 맨 아래로 내린다. */
  visible?: boolean;
}

export function ChatWindow({ deps: givenDeps = defaultDeps, headerAction, titleAction, onAssistantMessage, visible = true }: ChatWindowProps) {
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
  const etfProductCode = useSyncExternalStore(subscribe, () => planEtfProductCode(store.getState()));
  const etfProducts = useEtfProducts(deps.api, etfProductCode);
  const noEtf = useSyncExternalStore(subscribe, () => planHasNoEtf(store.getState()));
  const goalName = useSyncExternalStore(subscribe, () => planGoalName(store.getState()));

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

  const scrollRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);
  const wasVisibleRef = useRef(true);
  const pendingAssistantIdsRef = useRef(new Set<number>());

  // 사용자가 위로 올려 읽는 중이면 내리지 않는다. 숨겨졌다가 다시 보일 때는 읽던 자리가 없으므로 맨 아래로 내린다.
  useEffect(() => {
    const box = scrollRef.current;
    if (!box) return;
    if (visible && !wasVisibleRef.current) stickToBottomRef.current = true;
    wasVisibleRef.current = visible;
    if (visible && stickToBottomRef.current) box.scrollTop = box.scrollHeight;
  }, [chat.messages, visible]);

  // 대기(PENDING)로 본 어시스턴트 메시지가 끝났을 때만 알린다. 새로고침으로 복원된 지난 답변은 대기를 거치지 않는다.
  useEffect(() => {
    const pending = pendingAssistantIdsRef.current;
    let finished = false;
    for (const message of chat.messages) {
      if (message.role !== "ASSISTANT") continue;
      if (message.status === "PENDING") pending.add(message.id);
      else if (pending.delete(message.id)) finished = true;
    }
    if (finished) onAssistantMessage?.();
  }, [chat.messages, onAssistantMessage]);

  function rememberScrollPosition() {
    const box = scrollRef.current;
    if (!box) return;
    stickToBottomRef.current = box.scrollHeight - box.scrollTop - box.clientHeight <= STICK_TO_BOTTOM_PX;
  }

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

  const lastFailureId = [...chat.messages].reverse().find((m) => m.role === "ASSISTANT" && m.messageType === "FAILURE")?.id;
  const canRetry = settledStatus === "CALCULATED";

  return (
    <div
      data-testid="chat-window"
      role="region"
      aria-label={COPY.panelTitle}
      className="flex h-full min-h-0 flex-col bg-surface"
    >
      <header className="shrink-0 border-b border-border px-5 py-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-1.5">
            <h2 className="text-base font-bold text-text">{COPY.panelTitle}</h2>
            {titleAction}
          </div>
          <div className="flex items-center gap-1">
            {canRetry && (
              <button
                type="button"
                data-testid="chat-retry-explanation"
                disabled={retryExplanation.disabled}
                onClick={retryExplanation.retry}
                className="rounded-lg px-2 py-1 text-xs font-semibold text-primary hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted"
              >
                {COPY.retryExplanation}
              </button>
            )}
            {headerAction}
          </div>
        </div>
        <p className="mt-2 text-xs leading-relaxed text-text-muted">{COPY.panelNotice}</p>
      </header>
      {settledStatus === "ALREADY_EXCEEDED" && (
        <p data-testid="chat-already-exceeded" className={`${BUBBLE_ASSISTANT} mx-5 mt-4 shrink-0`}>
          {COPY.alreadyExceeded}
        </p>
      )}
      <div
        ref={scrollRef}
        onScroll={rememberScrollPosition}
        data-testid="chat-messages"
        className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-5 py-4"
      >
        {chat.messages.map((message) => (
          <Fragment key={message.id}>
            <Bubble
              message={message}
              onRetry={message.id === lastFailureId && canRetry ? retryExplanation.retry : undefined}
              retryDisabled={retryExplanation.disabled}
              planHasNoEtf={noEtf}
            />
            <ComparisonResult comparison={comparison} message={message} currentGoalName={goalName} />
          </Fragment>
        ))}
        <ComparisonNotices comparison={comparison} />
        {/* 설명이 먼저 도착해도 사용자가 아직 질문하지 않았다면 예시를 보여 준다. 빈 대화창이면 이것만 보인다. */}
        {!chat.messages.some((m) => m.role === "USER") && (
          <div data-testid="chat-empty" className="flex flex-col gap-3">
            <p className="text-sm text-text-muted">{COPY.emptyGuide}</p>
            <Suggestions disabled={sending || answerBlocking} onPick={(question) => void send(question)} />
          </div>
        )}
      </div>
      <div className="shrink-0 border-t border-border px-5 py-3">
        {chat.pollingFailed && (
          <p data-testid="chat-polling-failed" className="mb-2 text-xs text-danger">
            {COPY.pollingFailed}
          </p>
        )}
        {etfProductCode !== null && (
          <EtfChips
            products={etfProducts}
            currentCode={etfProductCode}
            disabled={sending || answerBlocking}
            onPick={(question) => void send(question)}
          />
        )}
        <div className="flex items-end gap-2">
          <textarea
            data-testid="chat-input"
            aria-label={COPY.inputPlaceholder}
            placeholder={COPY.inputPlaceholder}
            rows={2}
            maxLength={MAX_LENGTH}
            value={text}
            onChange={(event: ChangeEvent<HTMLTextAreaElement>) => setText(event.target.value)}
            className="min-h-11 flex-1 resize-none rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text focus-visible:outline-2 focus-visible:outline-primary"
          />
          <button
            type="button"
            data-testid="chat-send"
            disabled={!sendable}
            onClick={() => void send()}
            className="h-11 shrink-0 rounded-lg bg-primary px-4 text-sm font-semibold text-white hover:bg-primary-strong disabled:cursor-not-allowed disabled:bg-border disabled:text-text-muted"
          >
            {COPY.send}
          </button>
        </div>
        {inputError !== null && (
          <p data-testid="chat-input-error" className="mt-2 text-xs text-danger">
            {inputError}
          </p>
        )}
        {toast && (
          <p data-testid="chat-toast" role="alert" className="mt-2 text-xs text-danger">
            {COPY.sendFailed}
          </p>
        )}
        <p data-testid="chat-disclaimer" className="mt-2 text-[11px] leading-snug text-text-muted">
          {COPY.disclaimer}
        </p>
      </div>
    </div>
  );
}
