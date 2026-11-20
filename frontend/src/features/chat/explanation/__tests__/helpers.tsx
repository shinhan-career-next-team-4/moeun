// Issue #54 수용 테스트 공용 도구. 잠긴 파일이라 구현 쪽에서 고치지 않는다.
// 공개 인터페이스(ChatWindow + deps {api, polling, planStore}, 공유 store #13)만 쓴다. 구현 내부 이름에는 의존하지 않는다.
import { render, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";
import { planView } from "../../../../api/__tests__/helpers";
import { createPlanStore } from "../../../../api/planStore";
import { createPollingRegistry } from "../../../../api/polling";
import { ChatWindow } from "../../index";
import { flush, makeFakeApi, type Reply, type TestMessage } from "../../__tests__/helpers";

export const F3_PATH = "/api/chat/explanations";

// ---------------------------------------------------------------- 계획 결과(PlanView)
// 결정 U-4: 보낸 calcRunId는 탭(모듈) 단위로 기억된다. 테스트끼리 서로 막지 않게 calcRunId는 테스트마다 새 값을 쓴다.
let seq = 0;
export function freshId(): number {
  seq += 1;
  return 70_000 + seq * 10;
}

/** get-plan.200.json(rev·status만 바꾼 planView)에 calcRunId를 지정한 PlanView. */
export function view(revision: number, status: "CALCULATED" | "ALREADY_EXCEEDED", calcRunId: number) {
  const body = planView(revision, status);
  body.calcRunId = calcRunId;
  return body;
}

// ---------------------------------------------------------------- 공유 store
/**
 * 진짜 공유 store(#13). GET /api/plan은 스크립트 응답(planReplies)을 순서대로 돌려준다.
 * loadPlan은 spy로 감싸 호출 수를 센다(공개 메서드 호출 수, 결정 3 T-3).
 */
export function makeStore(planReplies: Reply[] = []) {
  const planApi = makeFakeApi({ get: planReplies });
  const navigations: string[] = [];
  const store = createPlanStore({
    api: { request: (method: "GET", path: string) => planApi.request(method, path) },
    navigate: (to) => navigations.push(to),
  });
  const loadPlan = vi.spyOn(store, "loadPlan");
  const planGets = () => planApi.calls.filter((c) => c.method === "GET" && c.path === "/api/plan");
  return { store, loadPlan, planGets, navigations };
}

// ---------------------------------------------------------------- 대화창 붙이기
/** 대화 API(F-1~F-3)용 가짜 api와 store를 묶어 ChatWindow를 그린다. F-3 응답은 post 큐에서 순서대로 나간다. */
export async function mountChat(
  store: unknown,
  replies: { get?: Reply[]; post?: Reply[] } = {},
  wrap: (node: ReactElement) => ReactElement = (node) => node,
) {
  const api = makeFakeApi({ get: replies.get ?? [{ messages: [], hasPending: false, answerPending: false }], post: replies.post ?? [{}] });
  const polling = createPollingRegistry();
  const deps = { api: api as any, polling, planStore: store as any };
  const utils = render(wrap(<ChatWindow deps={deps} />));
  await flush();
  const f3 = () => api.calls.filter((c) => c.method === "POST" && c.path === F3_PATH);
  const f3Bodies = () => f3().map((c) => c.body);
  return { api, deps, polling, f3, f3Bodies, ...utils };
}

// ---------------------------------------------------------------- 화면 읽기
export const chatWindow = () => screen.getByTestId("chat-window");
export const bubble = (id: number) =>
  screen.queryAllByTestId("chat-message").find((el) => el.getAttribute("data-message-id") === String(id));
export const retryButton = () => screen.queryByTestId("chat-retry-explanation");

/** 대화창에 오류·안내가 새로 그려지지 않았는지 보는 표지. 같은 값이면 아무것도 그려지지 않았다. */
export function chatMarkup(): string {
  return chatWindow().innerHTML;
}

/** 출처 영역(data-testid="chat-citations")을 뺀 말풍선 본문 텍스트. */
export function bodyTextWithoutCitations(el: HTMLElement): string {
  const clone = el.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('[data-testid="chat-citations"]').forEach((node) => node.remove());
  return clone.textContent ?? "";
}

// ---------------------------------------------------------------- 메시지
const createdAt = "2026-11-16T10:16:10+09:00";

export const explanationPending = (id: number): TestMessage => ({
  id,
  role: "ASSISTANT",
  messageType: null,
  status: "PENDING",
  content: null,
  citations: [],
  createdAt,
});

export const explanationDone = (
  id: number,
  content: string,
  citations: TestMessage["citations"] = [],
  messageType = "EXPLANATION",
): TestMessage => ({
  id,
  role: "ASSISTANT",
  messageType,
  status: "COMPLETED",
  content,
  citations,
  createdAt,
});

export const explanationFailed = (id: number, content: string): TestMessage => ({
  id,
  role: "ASSISTANT",
  messageType: "FAILURE",
  status: "FAILED",
  content,
  citations: [],
  createdAt,
});

export const chatPage = (messages: TestMessage[], hasPending: boolean) => ({ messages, hasPending, answerPending: false });

/**
 * 서버 상태를 흉내 내는 대화 응답. F-3이 202를 받기 전의 F-2는 before를, 받은 뒤의 F-2는 after를 차례로 돌려준다(끝이면 마지막 반복).
 * 호출 순서(복원과 F-3 중 무엇이 먼저 끝나는지)에 기대지 않으려고 쓴다. makeFakeApi의 응답 자리에 함수로 넣는다.
 */
export function explanationServer(after: ReturnType<typeof chatPage>[], options: { before?: ReturnType<typeof chatPage>; body?: unknown } = {}) {
  let accepted = false;
  let served = 0;
  const before = options.before ?? chatPage([], false);
  return {
    f3Reply: () => {
      accepted = true;
      return "body" in options ? options.body : {};
    },
    getReply: () => {
      if (!accepted) return before;
      const next = after[Math.min(served, after.length - 1)];
      served += 1;
      return next;
    },
    accepted: () => accepted,
  };
}
