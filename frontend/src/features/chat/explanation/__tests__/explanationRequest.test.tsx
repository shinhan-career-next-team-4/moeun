// @vitest-environment jsdom
// Issue #54: F-3 설명 요청 호출 지점(effect 하나)·초과 분기·404 회복·오류 무표시·202 뒤 폴링 재개.
// 대화창(ChatWindow)에 장착된 effect를 공개 인터페이스(deps의 api·planStore)로만 관찰한다(결정 U-2).
import { act, cleanup, render, screen } from "@testing-library/react";
import { StrictMode, type ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred, readMock } from "../../../../api/__tests__/helpers";
import { createApiClient } from "../../../../api/client";
import { createPollingRegistry } from "../../../../api/polling";
import { ChatWindow } from "../../index";
import {
  copy,
  flush,
  makeFakeApi,
  malformed,
  networkError,
  page,
  pendingMsg,
  problem,
  serverError,
  tick,
  unauthenticated,
  userMsg,
} from "../../__tests__/helpers";
import {
  bubble,
  chatMarkup,
  chatPage,
  chatWindow,
  F3_PATH,
  explanationDone,
  explanationFailed,
  explanationPending,
  explanationServer,
  freshId,
  makeStore,
  mountChat,
  retryButton,
  view,
} from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const notFound = () => problem(404, "NOT_FOUND");

function calculatedStore(revision: number, calcRunId: number, planReplies: Parameters<typeof makeStore>[0] = []) {
  const made = makeStore(planReplies);
  made.store.acceptPlanView(view(revision, "CALCULATED", calcRunId));
  return made;
}

describe("AC-1 결과가 그려지면 설명 요청(F-3)이 자동으로 나간다 (FR-AI-01, C12b-3a)", () => {
  it("store의 view가 CALCULATED이면 대화창이 열릴 때 POST /api/chat/explanations {calcRunId}를 한 번 보낸다", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("대화창이 먼저 열리고 결과(view)가 나중에 store에 들어오면 그때 한 번 보낸다", async () => {
    const x = freshId();
    const { store } = makeStore();
    const { f3Bodies } = await mountChat(store);
    await tick(3000);
    expect(f3Bodies()).toEqual([]);

    act(() => store.acceptPlanView(view(12, "CALCULATED", x)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("기존 질문의 답변을 기다리는 중(answerPending=true)에도 새 결과가 오면 F-3을 바로 한 번 보낸다(F-3은 ANSWER_PENDING으로 막지 않음)", async () => {
    const x = freshId();
    const { store } = makeStore();
    const { api, f3, f3Bodies } = await mountChat(store, {
      get: [page([userMsg(4501), pendingMsg(4502)], true, true)],
    });
    expect(screen.queryAllByTestId("chat-pending").map((el) => el.getAttribute("data-message-id"))).toEqual(["4502"]);
    expect(f3Bodies()).toEqual([]);

    act(() => store.acceptPlanView(view(12, "CALCULATED", x)));
    await flush();
    expect(f3().map((c) => [c.method, c.path])).toEqual([["POST", "/api/chat/explanations"]]);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(screen.queryAllByTestId("chat-pending").map((el) => el.getAttribute("data-message-id"))).toEqual(["4502"]); // 기존 답변은 아직 대기 중
    expect(api.posts()).toHaveLength(1); // 질문(F-1)을 새로 보내지 않는다
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });
});

describe("AC-5 미달·달성 결과 모두 설명을 요청한다 (D-062 예외)", () => {
  async function expectRequestedFor(file: string) {
    const x = freshId();
    const body = structuredClone(readMock(file).responses[0].body);
    expect(body.result.status).toBe("CALCULATED");
    body.calcRunId = x;
    const { store } = makeStore();
    store.acceptPlanView(body);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  }

  it("미달 결과(get-plan.200.json)에서 F-3을 보낸다: 초과 분기가 일반 결과를 막지 않는다", async () => {
    await expectRequestedFor("get-plan.200.json");
  });

  it("달성 결과(get-plan.200-after-apply.json, 3구간 모두 달성)에서 F-3을 보낸다", async () => {
    await expectRequestedFor("get-plan.200-after-apply.json");
  });
});

describe("AC-4 초과 결과(ALREADY_EXCEEDED)에서는 F-3을 보내지 않는다 (D-062, D-063, 결정 2 B1)", () => {
  it("초과 결과로 열린 대화창은 F-3을 보내지 않고 복원한 옛 설명 기록은 그대로 두며, 이후 미달 결과가 오면 그때 보낸다", async () => {
    const z = freshId();
    const y = freshId();
    const { store } = makeStore();
    store.acceptPlanView(view(12, "ALREADY_EXCEEDED", z));
    const { f3Bodies } = await mountChat(store, {
      get: [chatPage([explanationDone(501, "예전 계획에 대한 설명")], false)],
    });
    await tick(5000);
    expect(f3Bodies()).toEqual([]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(bubble(501)?.textContent).toContain("예전 계획에 대한 설명"); // 복원 기록은 숨기지 않는다(B1)

    act(() => store.acceptPlanView(view(13, "CALCULATED", y)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: y }]);
    expect(bubble(501)).toBeTruthy();
  });

  it("미달 결과에서 이미 보인 설명은 초과 결과로 바뀌어도 그대로 남고, F-3은 늘지 않는다", async () => {
    const x = freshId();
    const z = freshId();
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store, {
      get: [chatPage([explanationDone(511, "지금 계획에 대한 설명")], false)],
    });
    await tick(3000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    const shown = bubble(511);
    expect(shown?.textContent).toContain("지금 계획에 대한 설명");
    const shownMarkup = shown?.outerHTML;

    act(() => store.acceptRevision(13));
    act(() => store.acceptPlanView(view(13, "ALREADY_EXCEEDED", z)));
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(bubble(511)?.outerHTML).toBe(shownMarkup); // 복원·표시한 설명 기록을 지우지 않는다(B1, PD-011)
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
  });
});

describe("AC-6 같은 calcRunId로 다시 보내지 않는다 (C12b-3a, J-12f, 결정 U-4)", () => {
  it("리렌더·StrictMode 이중 effect·calcRunId가 그대로인 store 변경에도 한 번만 보낸다", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const wrap = (node: ReactElement) => <StrictMode>{node}</StrictMode>;
    const { f3Bodies, rerender, deps } = await mountChat(store, {}, wrap);
    rerender(wrap(<ChatWindow deps={deps} />));
    await flush();
    act(() => store.acceptRevision(13)); // revision만 먼저 바뀜(view는 옛 값)
    act(() => store.acceptPlanView(view(12, "CALCULATED", x))); // 같은 revision·같은 calcRunId의 새 응답 객체
    await tick(10_000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("calcRunId가 바뀔 때마다 새 값으로 한 번씩 보낸다(최초 보기 → 입력 수정 저장 → 적용 뒤 → 되돌리기 뒤)", async () => {
    const [x1, x2, x3, x4] = [freshId(), freshId(), freshId(), freshId()];
    const { store } = calculatedStore(12, x1);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x1 }]);

    act(() => store.acceptPlanView(view(13, "CALCULATED", x2))); // 입력 수정 저장(P-3 응답)
    await flush();
    act(() => store.acceptRevision(14)); // 적용 응답 {revision}
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x1 }, { calcRunId: x2 }]); // view가 오기 전에는 보내지 않는다
    act(() => store.acceptPlanView(view(14, "CALCULATED", x3))); // 재조회(loadPlan) 응답
    await flush();
    act(() => store.acceptRevision(15)); // 되돌리기 응답
    act(() => store.acceptPlanView(view(15, "CALCULATED", x4)));
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x1 }, { calcRunId: x2 }, { calcRunId: x3 }, { calcRunId: x4 }]);
  });

  it("같은 revision에서 calcRunId만 바뀌면(기준 데이터 재적재 등, C12a-6-6) 새 값으로 한 번 보내고, 같은 값의 새 응답 객체로는 다시 보내지 않는다", async () => {
    const x = freshId();
    const y = freshId();
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);

    act(() => store.acceptPlanView(view(12, "CALCULATED", y)));
    await flush();
    expect(store.getState().view?.calcRunId).toBe(y);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);

    act(() => store.acceptPlanView(view(12, "CALCULATED", y)));
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);
  });

  it("view가 null이거나 calcRunId가 없으면 보내지 않다가, calcRunId가 있는 CALCULATED 결과가 오면 보낸다", async () => {
    const x = freshId();
    const { store } = makeStore();
    const { f3Bodies } = await mountChat(store);
    await tick(3000);
    expect(store.getState().view).toBeNull();
    expect(f3Bodies()).toEqual([]);

    const missing = view(12, "CALCULATED", 0) as Record<string, unknown>;
    delete missing.calcRunId;
    act(() => store.acceptPlanView(missing as any));
    await tick(3000);
    const nullId = { ...view(13, "CALCULATED", 0), calcRunId: null };
    act(() => store.acceptPlanView(nullId as any));
    await tick(3000);
    expect(f3Bodies()).toEqual([]);

    act(() => store.acceptPlanView(view(14, "CALCULATED", x)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("대화창을 닫았다 다시 열어도 같은 calcRunId면 보내지 않고, 같은 store에 대화창이 둘 붙어도 새 calcRunId는 한 번만 보낸다", async () => {
    const x = freshId();
    const y = freshId();
    const { store } = calculatedStore(12, x);
    const first = await mountChat(store);
    expect(first.f3Bodies()).toEqual([{ calcRunId: x }]);
    first.unmount();

    const second = await mountChat(store);
    const third = await mountChat(store);
    await tick(3000);
    expect([...second.f3Bodies(), ...third.f3Bodies()]).toEqual([]);

    act(() => store.acceptPlanView(view(13, "CALCULATED", y)));
    await tick(3000);
    expect([...second.f3Bodies(), ...third.f3Bodies()]).toEqual([{ calcRunId: y }]);
  });

  it("F-3이 500·네트워크로 실패한 뒤 같은 store로 대화창을 닫았다 다시 열어도 같은 calcRunId로 다시 보내지 않는다(실패한 요청도 탭 단위로 기억)", async () => {
    for (const [label, error] of [
      ["500", serverError],
      ["network", networkError],
    ] as const) {
      const x = freshId();
      const { store } = calculatedStore(12, x);
      const first = await mountChat(store, { post: [error()] });
      await tick(3000);
      expect(first.f3Bodies(), label).toEqual([{ calcRunId: x }]);
      first.unmount();

      const second = await mountChat(store, { post: [{}] });
      await tick(3000);
      expect([...first.f3Bodies(), ...second.f3Bodies()], label).toEqual([{ calcRunId: x }]);
      second.unmount();
    }
  });

  it("store가 비워지면(clear: 로그아웃·401) 기억을 잊어, 다시 들어온 같은 calcRunId로 한 번 더 보낸다", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);

    act(() => store.clear());
    await flush();
    act(() => store.acceptPlanView(view(12, "CALCULATED", x)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: x }]);
  });

  it("clear와 같은 결과의 복원이 한 번의 갱신 묶음에서 일어나 중간 빈 화면이 없어도, 기억을 잊어 같은 calcRunId로 한 번 더 보낸다 (검토 impl-5·6)", async () => {
    const x = freshId();
    const { store } = calculatedStore(30, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);

    act(() => {
      store.clear();
      store.acceptPlanView(view(30, "CALCULATED", x));
    });
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: x }]);
  });

  it("대화창이 닫혀 있는 동안 store가 비워져도 잊는다: 다시 열면 같은 calcRunId로 보낸다", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const first = await mountChat(store);
    expect(first.f3Bodies()).toEqual([{ calcRunId: x }]);
    first.unmount();

    act(() => store.clear());
    act(() => store.acceptPlanView(view(12, "CALCULATED", x)));
    const second = await mountChat(store);
    expect(second.f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("새로고침(새 실행 환경)이면 브라우저 저장소가 그대로여도 같은 calcRunId로 한 번 다시 보내고, 그 안에서 다시 열면 보내지 않는다 (PD-008)", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const first = await mountChat(store);
    expect(first.f3Bodies()).toEqual([{ calcRunId: x }]);
    first.unmount();
    const again = await mountChat(store);
    expect(again.f3Bodies()).toEqual([]); // 같은 실행 환경의 다시 열기
    again.unmount();

    // 새로고침: 모듈을 새로 읽고 store도 새로 만든다. localStorage·sessionStorage는 비우지 않는다.
    vi.resetModules();
    const fresh = await import("../../index");
    const { createPlanStore } = await import("../../../../api/planStore");
    const { createPollingRegistry } = await import("../../../../api/polling");
    const freshStore = createPlanStore({ api: { request: async () => ({}) }, navigate: () => {} });
    freshStore.acceptPlanView(view(12, "CALCULATED", x));
    const mountFresh = async () => {
      const api = makeFakeApi({ get: [chatPage([], false)], post: [{}] });
      const deps = { api: api as any, polling: createPollingRegistry(), planStore: freshStore };
      const utils = render(<fresh.ChatWindow deps={deps} />);
      await flush();
      const bodies = () => api.calls.filter((c) => c.method === "POST" && c.path === "/api/chat/explanations").map((c) => c.body);
      return { bodies, ...utils };
    };
    const reloaded = await mountFresh();
    await tick(3000);
    expect(reloaded.bodies()).toEqual([{ calcRunId: x }]);
    reloaded.unmount();
    const reopened = await mountFresh();
    await tick(3000);
    expect(reopened.bodies()).toEqual([]);
  });
});

describe("AC-7 F-3 202 응답 본문은 쓰지 않고 폴링을 다시 시작한다 (C12b-3a, C12b-5, 결정 4 B1)", () => {
  /** 대화창에 data-message-id가 id인 요소가 한 번이라도 생겼는지 기록한다(그렸다가 지운 경우도 잡는다). */
  function watchMessageId(id: number) {
    const seen: string[] = [];
    const hit = (node: Node) =>
      node instanceof HTMLElement && (node.getAttribute("data-message-id") === String(id) || node.querySelector(`[data-message-id="${id}"]`) !== null);
    const handle = (records: MutationRecord[]) => {
      for (const record of records) {
        if (record.type === "attributes" && hit(record.target)) seen.push((record.target as HTMLElement).outerHTML);
        record.addedNodes.forEach((node) => {
          if (hit(node)) seen.push((node as HTMLElement).outerHTML);
        });
      }
    };
    const observer = new MutationObserver(handle);
    observer.observe(chatWindow(), { childList: true, subtree: true, attributes: true, attributeFilter: ["data-message-id"] });
    if (hit(chatWindow())) seen.push("already present");
    return {
      seen: () => {
        handle(observer.takeRecords());
        return seen;
      },
      stop: () => observer.disconnect(),
    };
  }

  async function expectPollingResumed(body: unknown) {
    const x = freshId();
    const f3 = deferred<unknown>();
    const firstAfter = deferred<unknown>(); // 202 뒤 첫 F-2 응답은 잡아 둔다: 그 사이에 202 값으로 무언가 그리는지 본다
    const { store } = calculatedStore(12, x);
    const { api, f3Bodies } = await mountChat(store, {
      get: [
        chatPage([], false), // 최초 복원: 대기 없음 → 폴링이 멈춘다
        firstAfter.promise,
        chatPage([explanationDone(4001, "설명 본문입니다")], false),
      ],
      post: [f3.promise],
    });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    await tick(3000);
    expect(api.gets()).toHaveLength(1); // 202 전에는 멈춰 있다(최초 복원만으로는 설명을 받지 못한다)
    expect(bubble(4001)).toBeUndefined();
    const ghost = watchMessageId(9999);

    await act(async () => f3.resolve(body));
    await flush();
    expect(ghost.seen()).toEqual([]); // 202 직후: 응답 값의 id로 대기 표시·말풍선을 만들지 않는다
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(0);

    await tick(1000);
    expect(api.gets()).toHaveLength(2); // 202 값과 무관하게 F-2를 다시 보낸다(응답은 아직 오지 않음)
    expect(ghost.seen()).toEqual([]);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(0);

    await act(async () => firstAfter.resolve(chatPage([explanationPending(4001)], true)));
    await flush();
    expect(screen.queryAllByTestId("chat-pending").map((el) => el.getAttribute("data-message-id"))).toEqual(["4001"]);
    await tick(1000);
    await tick(1000);
    expect(api.gets().length).toBeGreaterThanOrEqual(3);
    expect(bubble(4001)?.getAttribute("data-message-type")).toBe("EXPLANATION");
    expect(bubble(4001)?.textContent).toContain("설명 본문입니다");
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(ghost.seen()).toEqual([]); // 처음부터 끝까지 id 9999는 한 번도 그려지지 않았다
    ghost.stop();
  }

  it("202 빈 객체 {}: 본문을 쓰지 않고 폴링을 재개해 설명이 대화창에 도착한다", async () => {
    await expectPollingResumed({});
  });

  it("202 null: 본문을 쓰지 않고 폴링을 재개해 설명이 대화창에 도착한다", async () => {
    await expectPollingResumed(null);
  });

  it("202 계약 밖 필드(assistantMessageId 등): 본문을 쓰지 않고 폴링을 재개해 설명이 대화창에 도착한다", async () => {
    await expectPollingResumed({ assistantMessageId: 9999, extra: "x" });
  });

  it("이전 calcRunId의 202가 늦게 오면 무시한다: 새 calcRunId의 F-3이 500으로 끝나 멈춘 폴링을 다시 돌리지 않는다 (PD-008)", async () => {
    const x = freshId();
    const y = freshId();
    const late = deferred<unknown>();
    const { store } = calculatedStore(12, x);
    const { api, f3Bodies } = await mountChat(store, { get: [chatPage([], false)], post: [late.promise, serverError()] });
    act(() => store.acceptPlanView(view(13, "CALCULATED", y)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);
    await tick(3000);
    const gets = api.gets().length;

    await act(async () => late.resolve({}));
    await flush();
    await tick(5000);
    expect(api.gets()).toHaveLength(gets);
    expect(f3Bodies()).toHaveLength(2);
  });

  it("store가 비워지기(clear) 전 세대의 202가 늦게 오면 무시한다: 같은 calcRunId가 다시 들어와 있어도 폴링을 다시 돌리지 않는다 (PD-008)", async () => {
    const x = freshId();
    const late = deferred<unknown>();
    const { store } = calculatedStore(12, x);
    const { api, f3Bodies } = await mountChat(store, { get: [chatPage([], false)], post: [late.promise, serverError()] });
    act(() => store.clear());
    act(() => store.acceptPlanView(view(12, "CALCULATED", x)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: x }]); // 새 세대의 요청은 500으로 끝난다
    await tick(3000);
    const gets = api.gets().length;

    await act(async () => late.resolve({})); // 옛 세대의 늦은 202
    await flush();
    await tick(5000);
    expect(api.gets()).toHaveLength(gets);
  });
});

describe("AC-7 요청 중 대화창을 닫았다 다시 열어도 현재 결과·세대의 202는 폴링을 재개한다 (P-38, 검토 impl-3·4)", () => {
  it("F-3 요청이 떠 있는 동안 대화창을 닫고 같은 store로 다시 열면, 그 뒤 온 202는 다시 열린 대화창의 F-2를 재개해 설명이 보이고 F-3은 합계 한 번이다", async () => {
    const x = freshId();
    const f3 = deferred<unknown>();
    const { store } = calculatedStore(12, x);
    const first = await mountChat(store, { post: [f3.promise] });
    expect(first.f3Bodies()).toEqual([{ calcRunId: x }]);
    first.unmount();

    const second = await mountChat(store, { get: [chatPage([], false), chatPage([explanationDone(4101, "설명 본문입니다")], false)] });
    await tick(3000);
    expect(second.f3Bodies()).toEqual([]);
    expect(second.api.gets()).toHaveLength(1); // 최초 복원만 하고 멈춰 있다
    expect(bubble(4101)).toBeUndefined();

    await act(async () => f3.resolve({}));
    await flush();
    await tick(2000);
    expect(second.api.gets()).toHaveLength(2);
    expect(bubble(4101)?.textContent).toContain("설명 본문입니다");
    expect(first.f3Bodies()).toHaveLength(1);
    expect(second.f3Bodies()).toEqual([]);
  });
});

describe("AC-14 폴링 재개가 진행 중인 폴링을 버리지 않는다 (C12b-5, 잠정 P-31)", () => {
  it("폴링 요청이 떠 있는 동안 F-3 202가 와도 그 응답을 그대로 반영하고(다음 주기까지 미루지 않는다), 그 응답이 F-3 전에 읽은 hasPending=false여도 F-2를 더 보내 새 설명을 받는다", async () => {
    const x = freshId();
    const f3 = deferred<unknown>();
    const inFlight = deferred<unknown>();
    const { store } = calculatedStore(12, x);
    const { api, f3Bodies } = await mountChat(store, {
      get: [
        chatPage([userMsg(4401), pendingMsg(4402)], true),
        inFlight.promise, // F-3 접수 전에 서버가 읽은 응답: 질문 답변 완료, hasPending=false
        chatPage([explanationPending(4403)], true),
        chatPage([explanationDone(4403, "새 설명입니다")], false),
      ],
      post: [f3.promise],
    });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    await tick(1000); // 두 번째 F-2(afterId)가 떠 있다
    expect(api.gets()).toHaveLength(2);

    await act(async () => f3.resolve({}));
    await flush();
    await act(async () => inFlight.resolve(chatPage([explanationDone(4402, "질문에 대한 답", [], "ANSWER")], false)));
    await flush();
    expect(bubble(4402)?.textContent).toContain("질문에 대한 답"); // 떠 있던 응답을 즉시 반영
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);

    await tick(1000);
    await tick(1000);
    await tick(1000);
    expect(api.gets().length).toBeGreaterThanOrEqual(4); // 늦은 hasPending=false로 멈추지 않고 F-2를 더 보낸다
    expect(bubble(4403)?.getAttribute("data-message-type")).toBe("EXPLANATION");
    expect(bubble(4403)?.textContent).toContain("새 설명입니다");
    expect(bubble(4402)?.textContent).toContain("질문에 대한 답");
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
  });

  it("F-3 202로 이어 간 폴링은 떠 있던 답변(4402)을 반영한 뒤 afterId=4402로 이어 가고(전체 목록을 다시 받지 않음), 설명 완료(hasPending=false)에서 멈춘다 (검토 plan-5)", async () => {
    const x = freshId();
    const f3 = deferred<unknown>();
    const inFlight = deferred<unknown>();
    const { store } = calculatedStore(12, x);
    const { api, f3Bodies } = await mountChat(store, {
      get: [
        chatPage([userMsg(4401), pendingMsg(4402)], true),
        inFlight.promise,
        chatPage([explanationPending(4403)], true),
        chatPage([explanationDone(4403, "새 설명입니다")], false), // 끝이면 이 응답이 반복된다
      ],
      post: [f3.promise],
    });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    await tick(1000);
    expect(api.gets()).toHaveLength(2);

    await act(async () => f3.resolve({}));
    await flush();
    await act(async () => inFlight.resolve(chatPage([explanationDone(4402, "질문에 대한 답", [], "ANSWER")], false)));
    await flush();
    for (let i = 0; i < 5; i += 1) await tick(1000);
    expect(bubble(4403)?.textContent).toContain("새 설명입니다");
    const followUps = api.gets().slice(2).map((c) => c.path);
    expect(followUps.length).toBeGreaterThanOrEqual(2);
    expect(followUps.every((p) => p === "/api/chat/messages?afterId=4402")).toBe(true);
    const settled = api.gets().length;
    await tick(10_000);
    expect(api.gets()).toHaveLength(settled); // 완료 뒤에는 더 부르지 않는다
  });

  it("F-3 202로 후속 F-2가 필요해진 직후 떠 있던 F-2가 401을 받으면(공통 래퍼) 로그인으로 가고 store가 비워지며 F-2를 더 보내지 않는다 (검토 plan-6)", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const heldF3 = deferred<Response>();
    const heldF2 = deferred<Response>();
    const calls: Array<{ method: string; path: string }> = [];
    const json = (status: number, body: unknown, type = "application/json") =>
      new Response(JSON.stringify(body), { status, headers: { "content-type": type } });
    let chatGets = 0;
    const fakeFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const method = init?.method ?? "GET";
      calls.push({ method, path });
      if (method === "POST" && path === F3_PATH) return heldF3.promise;
      if (method === "GET" && path.startsWith("/api/chat/messages")) {
        chatGets += 1;
        if (chatGets === 1) return json(200, chatPage([userMsg(4701), pendingMsg(4702)], true));
        if (chatGets === 2) return heldF2.promise;
        return json(200, chatPage([explanationPending(4703)], true));
      }
      throw new Error(`스크립트에 없는 호출: ${method} ${path}`);
    }) as typeof fetch;
    const polling = createPollingRegistry();
    const navigations: string[] = [];
    const client = createApiClient({
      mode: "live",
      fetch: fakeFetch,
      planStore: { clear: () => store.clear(), loadPlan: (options) => store.loadPlan(options) },
      polling,
      navigate: (to) => navigations.push(to),
      getPathname: () => "/result",
    });
    render(<ChatWindow deps={{ api: client as any, polling, planStore: store as any }} />);
    await flush();
    const f2 = () => calls.filter((c) => c.method === "GET" && c.path.startsWith("/api/chat/messages"));
    const f3 = () => calls.filter((c) => c.method === "POST" && c.path === F3_PATH);
    expect(f3()).toHaveLength(1);
    await tick(1000);
    expect(f2()).toHaveLength(2); // 두 번째 F-2가 떠 있다

    await act(async () => heldF3.resolve(json(202, {}))); // 후속 F-2가 필요해진다
    await flush();
    const beforeAuth = f2().length;
    await act(async () =>
      heldF2.resolve(json(401, { status: 401, code: "UNAUTHENTICATED", errors: [] }, "application/problem+json")),
    );
    await flush();
    await tick(10_000);
    expect(navigations).toEqual(["/login"]);
    expect(store.getState().view).toBeNull();
    expect(store.getState().revision).toBeNull();
    expect(f2()).toHaveLength(beforeAuth); // 401 뒤 F-2 추가 호출 0회
    expect(f3()).toHaveLength(1);
  });

  it("F-2가 연속 실패(3회)로 멈추고 실패 안내가 보인 뒤 새 결과의 F-3이 202를 받으면 폴링을 다시 시작해 실패 안내가 사라지고 새 설명을 받는다", async () => {
    const y = freshId();
    const { store } = makeStore(); // 처음엔 결과가 없어 F-3이 나가지 않는다
    const { api, f3Bodies } = await mountChat(store, {
      get: [
        page([userMsg(4601), pendingMsg(4602)], true, true), // 최초 복원: 답변 대기 중 → 폴링
        serverError(),
        serverError(),
        serverError(), // 3회 연속 실패 → 실패 안내와 함께 멈춤(#52)
        chatPage([explanationDone(4602, "질문에 대한 답", [], "ANSWER"), explanationPending(4603)], true),
        chatPage([explanationDone(4603, "새 결과에 대한 설명")], false),
      ],
      post: [{}],
    });
    await tick(1000);
    await tick(1000);
    await tick(1000);
    expect(api.gets()).toHaveLength(4);
    expect(screen.queryByTestId("chat-polling-failed")).not.toBeNull();
    await tick(5000);
    expect(api.gets()).toHaveLength(4); // 멈춰 있다
    expect(f3Bodies()).toEqual([]);

    act(() => store.acceptPlanView(view(12, "CALCULATED", y)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: y }]);
    await tick(1000);
    await tick(1000);
    await tick(1000);
    expect(api.gets().length).toBeGreaterThanOrEqual(6); // 실패로 멈춘 폴링도 F-3 202 뒤 다시 돈다(P-31)
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    expect(bubble(4602)?.textContent).toContain("질문에 대한 답");
    expect(bubble(4603)?.getAttribute("data-message-type")).toBe("EXPLANATION");
    expect(bubble(4603)?.textContent).toContain("새 결과에 대한 설명");
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
  });
});

describe("AC-3 설명이 FAILED로 도착하면 실패 안내만 보인다 (FR-AI-04, C12b-3)", () => {
  it("F-3 뒤 폴링으로 FAILED 설명이 오면 대화창에 문구표 실패 안내가 보이고 공유 계획 상태는 그대로다", async () => {
    const x = freshId();
    const { store, loadPlan, planGets } = calculatedStore(12, x);
    const before = store.getState();
    const server = explanationServer([
      chatPage([explanationPending(4101)], true),
      chatPage([explanationFailed(4101, copy["chat.failed"])], false),
    ]);
    const { f3Bodies } = await mountChat(store, { get: [server.getReply], post: [server.f3Reply] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    const pendingIds = () => screen.queryAllByTestId("chat-pending").map((el) => el.getAttribute("data-message-id"));
    let sawPending = pendingIds().includes("4101");
    for (let i = 0; i < 3 && !sawPending; i += 1) {
      await tick(1000);
      sawPending = pendingIds().includes("4101");
    }
    expect(sawPending).toBe(true); // 첫 F-2로 받은 같은 id의 대기 표시가 실제로 보였다(검토 plan-9)
    await tick(1000);
    await tick(1000);
    await tick(1000);
    expect(pendingIds()).not.toContain("4101"); // FAILED를 받으면 같은 id의 대기 표시가 사라진다
    expect(screen.queryAllByTestId("chat-message").filter((el) => el.getAttribute("data-message-id") === "4101")).toHaveLength(1);
    const failed = bubble(4101);
    expect(failed?.getAttribute("data-status")).toBe("FAILED");
    expect(failed?.getAttribute("data-message-type")).toBe("FAILURE");
    expect(failed?.textContent).toContain(copy["chat.failed"]);
    expect(store.getState()).toBe(before);
    expect(loadPlan).not.toHaveBeenCalled();
    expect(planGets()).toHaveLength(0);
  });

  it("FAILED 본문은 프론트 문구가 아니라 서버 content 그대로 보인다(문구표가 바뀐 서버 문장)", async () => {
    const x = freshId();
    const serverText = "설명을 지금 만들 수 없어요. 서버 문구표의 새 문장입니다";
    expect(serverText).not.toBe(copy["chat.failed"]);
    const { store, loadPlan } = calculatedStore(12, x);
    const before = store.getState();
    const server = explanationServer([chatPage([explanationPending(4111)], true), chatPage([explanationFailed(4111, serverText)], false)]);
    const { f3Bodies } = await mountChat(store, { get: [server.getReply], post: [server.f3Reply] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    await tick(1000);
    await tick(1000);
    await tick(1000);
    const failed = bubble(4111);
    expect(failed?.getAttribute("data-status")).toBe("FAILED");
    expect(failed?.textContent).toBe(serverText);
    expect(failed?.textContent).not.toContain(copy["chat.failed"]);
    expect(store.getState()).toBe(before);
    expect(loadPlan).not.toHaveBeenCalled();
  });
});

describe("AC-8 F-3 404 NOT_FOUND (C12b-3a, C12a-6-5, 결정 2 B2·B3, 결정 3 T-3)", () => {
  it("404면 loadPlan()으로 다시 불러오고, calcRunId가 바뀌면 새 값으로 한 번만 다시 보내며, 그 설명은 폴링으로 정상 표시된다", async () => {
    const x = freshId();
    const y = freshId();
    const { store, loadPlan, planGets } = calculatedStore(12, x, [view(13, "CALCULATED", y)]);
    const server = explanationServer([
      chatPage([explanationPending(4201)], true),
      chatPage([explanationDone(4201, "새 계획 설명")], false),
    ]);
    const { api, f3Bodies } = await mountChat(store, { get: [server.getReply], post: [notFound(), server.f3Reply] });
    await flush();
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect((loadPlan.mock.calls[0][0] as { fresh?: boolean } | undefined)?.fresh).not.toBe(true); // single-flight 합류, fresh 강제 없음(PD-008)
    expect(planGets()).toHaveLength(1);
    expect(store.getState().view?.calcRunId).toBe(y);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);
    // GET /api/plan은 store의 loadPlan()만 부른다. 대화 api로 직접 부르지 않는다(C12a-6-5)
    expect(api.calls.some((c) => c.path.startsWith("/api/plan"))).toBe(false);

    await tick(1000);
    await tick(1000);
    await tick(1000);
    expect(bubble(4201)?.textContent).toContain("새 계획 설명");
    await tick(10_000);
    expect(f3Bodies()).toHaveLength(2);
    expect(loadPlan).toHaveBeenCalledTimes(1);
  });

  it("다시 불러온 calcRunId가 같으면 다시 보내지 않고 아무것도 그리지 않는다", async () => {
    const x = freshId();
    const pending = deferred<unknown>();
    const { store, loadPlan } = calculatedStore(12, x, [view(12, "CALCULATED", x)]);
    const { f3Bodies } = await mountChat(store, { post: [pending.promise] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    const before = chatMarkup();

    await act(async () => pending.reject(notFound()));
    await flush();
    await tick(10_000);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(chatMarkup()).toBe(before);
  });

  it("다시 보낸 F-3도 404면 더 불러오지 않고 아무것도 그리지 않는다", async () => {
    const x = freshId();
    const y = freshId();
    const z = freshId();
    const { store, loadPlan } = calculatedStore(12, x, [view(13, "CALCULATED", y), view(14, "CALCULATED", z)]);
    const { f3Bodies } = await mountChat(store, { post: [notFound(), notFound()] });
    await tick(10_000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(0);
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
    expect(screen.queryByTestId("chat-toast")).toBeNull();
  });

  it("한 연쇄의 회복 횟수 제한은 다음 결과에 넘어가지 않는다: 새로 들어온 결과의 첫 404는 다시 한 번 불러오고 그 결과의 calcRunId로만 다시 보낸다", async () => {
    const [x, y, z, w] = [freshId(), freshId(), freshId(), freshId()];
    const { store, loadPlan } = calculatedStore(12, x, [view(13, "CALCULATED", y), view(16, "CALCULATED", w)]);
    const { f3Bodies } = await mountChat(store, { post: [notFound(), notFound(), notFound(), {}] });
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]); // x 404 → 재조회 → y 404 → 멈춤
    expect(loadPlan).toHaveBeenCalledTimes(1);

    act(() => store.acceptPlanView(view(15, "CALCULATED", z))); // 사용자가 따로 입력을 저장해 새 결과가 들어옴
    await flush();
    await tick(5000);
    expect(loadPlan).toHaveBeenCalledTimes(2); // z의 첫 404는 새 연쇄라 한 번 더 불러온다
    expect(store.getState().view?.calcRunId).toBe(w);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }, { calcRunId: z }, { calcRunId: w }]);
    await tick(10_000);
    expect(loadPlan).toHaveBeenCalledTimes(2);
    expect(f3Bodies()).toHaveLength(4);
  });

  it("404 뒤 다시 불러오기가 늦게 500으로 실패해도 loadPlan은 대기 중·실패 뒤 모두 한 번이고 아무것도 그리지 않는다 (T-3)", async () => {
    const x = freshId();
    const slowGet = deferred<unknown>();
    const slowF3 = deferred<unknown>();
    const { store, loadPlan, planGets } = calculatedStore(12, x, [slowGet.promise]);
    const { f3Bodies } = await mountChat(store, { post: [slowF3.promise] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    const before = chatMarkup(); // 404를 받기 전의 화면(검토 plan-3: 기준을 404 처리 전으로)
    expect(loadPlan).not.toHaveBeenCalled();

    await act(async () => slowF3.reject(notFound()));
    await flush();
    expect(chatMarkup()).toBe(before); // 404 처리 직후
    expect(loadPlan).toHaveBeenCalledTimes(1);
    await tick(10_000);
    expect(loadPlan).toHaveBeenCalledTimes(1); // 대기 중에 다시 부르지 않는다
    expect(chatMarkup()).toBe(before); // GET 대기 중

    await act(async () => slowGet.reject(serverError()));
    await flush();
    await tick(10_000);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(planGets()).toHaveLength(1);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(chatMarkup()).toBe(before);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("다시 불러온 결과가 초과(ALREADY_EXCEEDED)면 다시 보내지 않는다", async () => {
    const x = freshId();
    const z = freshId();
    const { store, loadPlan } = calculatedStore(12, x, [view(13, "ALREADY_EXCEEDED", z)]);
    const { f3Bodies } = await mountChat(store, { post: [notFound()] });
    await tick(5000);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(store.getState().view?.calcRunId).toBe(z);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("404가 오기 전에 calcRunId가 이미 바뀌었으면 그 404로는 다시 불러오지 않는다 (B3)", async () => {
    const x = freshId();
    const y = freshId();
    const late = deferred<unknown>();
    const { store, loadPlan } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store, { post: [late.promise, {}] });
    act(() => store.acceptPlanView(view(13, "CALCULATED", y)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);

    await act(async () => late.reject(notFound()));
    await flush();
    await tick(5000);
    expect(loadPlan).not.toHaveBeenCalled();
    expect(f3Bodies()).toHaveLength(2);
  });

  it("404가 오기 전에 store가 비워졌으면(clear) 같은 calcRunId가 다시 들어와 있어도 그 404로는 다시 불러오지 않는다 (B3)", async () => {
    const x = freshId();
    const late = deferred<unknown>();
    const { store, loadPlan } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store, { post: [late.promise, {}] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);

    act(() => store.clear());
    act(() => store.acceptPlanView(view(12, "CALCULATED", x)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: x }]); // 새 세대의 요청(U-4)

    await act(async () => late.reject(notFound())); // 옛 세대의 늦은 404
    await flush();
    await tick(5000);
    expect(loadPlan).not.toHaveBeenCalled();
  });

  it("이전 결과의 다시 불러오기가 진행 중일 때 따로 들어온 새 결과의 404도 그 revision에서 다시 불러오고, 그 재조회의 새 calcRunId로만 다시 보내며, 늦게 끝난 이전 재조회는 현재 결과를 덮지 않는다", async () => {
    const [x, z, w] = [freshId(), freshId(), freshId()];
    const oldGet = deferred<unknown>(); // revision 12에서 시작한 재조회(x의 404)
    const newGet = deferred<unknown>(); // revision 13에서 시작한 재조회(z의 404)
    const { store, loadPlan, planGets } = calculatedStore(12, x, [oldGet.promise, newGet.promise]);
    const { f3Bodies } = await mountChat(store, { post: [notFound(), notFound(), {}] });
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(planGets()).toHaveLength(1);

    act(() => store.acceptPlanView(view(13, "CALCULATED", z))); // 재조회 응답 전에 사용자가 입력을 저장(P-3 응답)
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: z }]);
    expect(loadPlan).toHaveBeenCalledTimes(2); // z의 404는 새 연쇄: 이전 재조회가 떠 있어도 무시하지 않는다
    expect(planGets()).toHaveLength(2); // revision이 달라 이전 GET에 합류하지 않고 새로 보낸다(C12a-6-5)

    await act(async () => newGet.resolve(view(14, "CALCULATED", w))); // 새 재조회가 먼저 끝남
    await flush();
    expect(store.getState().view?.calcRunId).toBe(w);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: z }, { calcRunId: w }]);

    await act(async () => oldGet.resolve(view(12, "CALCULATED", x))); // 이전 재조회가 늦게 끝남
    await flush();
    await tick(10_000);
    expect(store.getState().view?.calcRunId).toBe(w);
    expect(store.getState().revision).toBe(14);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: z }, { calcRunId: w }]);
    expect(loadPlan).toHaveBeenCalledTimes(2);
    expect(planGets()).toHaveLength(2);
  });

  it("앞선 재조회가 별도로 들어온 결과 z와 같은 값을 돌려준 뒤에도 z의 첫 404는 새 연쇄라 한 번 더 불러오고, 그 새 결과 w로 한 번 다시 보낸다 (검토 impl-1·2)", async () => {
    const [x, z, w] = [freshId(), freshId(), freshId()];
    const oldGet = deferred<unknown>(); // x의 404 뒤 재조회(revision 12에서 출발)
    const zF3 = deferred<unknown>();
    const { store, loadPlan, planGets } = calculatedStore(12, x, [oldGet.promise, view(14, "CALCULATED", w)]);
    const { f3Bodies } = await mountChat(store, { post: [notFound(), zF3.promise, {}] });
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(loadPlan).toHaveBeenCalledTimes(1);

    act(() => store.acceptPlanView(view(13, "CALCULATED", z))); // 재조회 응답 전에 입력 저장(P-3 응답): z의 F-3은 이 경로에서 출발
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: z }]);

    await act(async () => oldGet.resolve(view(13, "CALCULATED", z))); // 앞선 재조회가 같은 z를 돌려준다
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: z }]); // 같은 id라 다시 보내지 않는다
    expect(loadPlan).toHaveBeenCalledTimes(1);

    await act(async () => zF3.reject(notFound())); // 이미 나가 있던 z의 F-3이 404
    await flush();
    expect(loadPlan).toHaveBeenCalledTimes(2); // 회복 연쇄에 편입되지 않으므로 새 연쇄로 한 번 더 불러온다
    expect(planGets()).toHaveLength(2);
    expect(store.getState().view?.calcRunId).toBe(w);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: z }, { calcRunId: w }]);
  });

  it("대화창이 닫힌 뒤 도착한 같은 결과·세대의 404는 공유 store에서 회복하고(loadPlan 1회), 닫힌 대화창은 그리지 않으며, 다시 열면 회복된 calcRunId로 설명을 받는다 (S2 기준 변경, 검토 plan-3)", async () => {
    const x = freshId();
    const y = freshId();
    const late = deferred<unknown>();
    const { store, loadPlan, planGets } = calculatedStore(12, x, [view(13, "CALCULATED", y)]);
    const first = await mountChat(store, { post: [late.promise] });
    expect(first.f3Bodies()).toEqual([{ calcRunId: x }]);
    first.unmount();
    const closedCalls = first.api.calls.length;

    await act(async () => late.reject(notFound())); // 같은 calcRunId·같은 clear() 세대의 404
    await flush();
    await tick(5000);
    expect(loadPlan).toHaveBeenCalledTimes(1); // 공유 store에서 회복한다
    expect(planGets()).toHaveLength(1);
    expect(store.getState().view?.calcRunId).toBe(y);
    expect(first.api.calls).toHaveLength(closedCalls); // 닫힌 대화창은 F-2·F-3을 더 보내지 않는다(로컬 UI 갱신 생략)
    expect(screen.queryByTestId("chat-window")).toBeNull();

    const server = explanationServer([
      chatPage([explanationPending(4251)], true),
      chatPage([explanationDone(4251, "다시 연 대화창의 설명")], false),
    ]);
    const second = await mountChat(store, { get: [server.getReply], post: [server.f3Reply] });
    await flush();
    expect(second.f3Bodies()).toEqual([{ calcRunId: y }]); // 다시 열면 회복된 결과의 설명을 요청한다
    await tick(1000);
    await tick(1000);
    await tick(1000);
    expect(bubble(4251)?.textContent).toContain("다시 연 대화창의 설명");
    await tick(10_000);
    expect([...first.f3Bodies(), ...second.f3Bodies()]).toEqual([{ calcRunId: x }, { calcRunId: y }]);
    expect(loadPlan).toHaveBeenCalledTimes(1);
  });

  it("대화창이 닫힌 동안 x의 404로 y를 얻고, 다시 연 대화창의 y도 404면 같은 회복 연쇄라 더 불러오지 않고 아무것도 그리지 않는다 (검토 plan-4)", async () => {
    const [x, y, z] = [freshId(), freshId(), freshId()];
    const late = deferred<unknown>();
    const { store, loadPlan, planGets } = calculatedStore(12, x, [view(13, "CALCULATED", y), view(14, "CALCULATED", z)]);
    const first = await mountChat(store, { post: [late.promise] });
    expect(first.f3Bodies()).toEqual([{ calcRunId: x }]);
    first.unmount();

    await act(async () => late.reject(notFound()));
    await flush();
    await tick(5000);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(store.getState().view?.calcRunId).toBe(y);

    const yReply = deferred<unknown>();
    const second = await mountChat(store, { post: [yReply.promise] });
    await flush();
    expect(second.f3Bodies()).toEqual([{ calcRunId: y }]);
    const before = chatMarkup();

    await act(async () => yReply.reject(notFound())); // 다시 연 대화창에서 y도 404
    await flush();
    await tick(10_000);
    expect([...first.f3Bodies(), ...second.f3Bodies()]).toEqual([{ calcRunId: x }, { calcRunId: y }]); // F-3 합계 2회
    expect(loadPlan).toHaveBeenCalledTimes(1); // 회복 연쇄는 재마운트를 넘어 이어진다: 합계 1회
    expect(planGets()).toHaveLength(1);
    expect(store.getState().view?.calcRunId).toBe(y);
    expect(chatMarkup()).toBe(before); // 새 안내 없음
  });
});

describe("AC-9 F-3 400·500·네트워크·401이면 대화창에 아무것도 그리지 않는다 (C12b-3a)", () => {
  async function expectNothingDrawn(error: () => Error) {
    const x = freshId();
    const pending = deferred<unknown>();
    const { store, loadPlan } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store, { post: [pending.promise] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    const before = chatMarkup();

    await act(async () => pending.reject(error()));
    await flush();
    await tick(10_000);
    expect(chatMarkup()).toBe(before);
    expect(screen.queryByTestId("chat-toast")).toBeNull();
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    expect(loadPlan).not.toHaveBeenCalled();
    expect(f3Bodies()).toHaveLength(1);
  }

  it("400 MALFORMED_REQUEST: 오류 문구 없이 그대로 두고, 결과를 다시 불러오지 않으며, 같은 calcRunId로 다시 보내지 않는다", async () => {
    await expectNothingDrawn(malformed);
  });

  it("500 INTERNAL_ERROR: 오류 문구 없이 그대로 두고, 결과를 다시 불러오지 않으며, 같은 calcRunId로 다시 보내지 않는다", async () => {
    await expectNothingDrawn(serverError);
  });

  it("네트워크 실패: 오류 문구 없이 그대로 두고, 결과를 다시 불러오지 않으며, 같은 calcRunId로 다시 보내지 않는다", async () => {
    await expectNothingDrawn(networkError);
  });

  it("401 UNAUTHENTICATED: 오류 문구 없이 그대로 두고, 결과를 다시 불러오지 않으며, 같은 calcRunId로 다시 보내지 않는다", async () => {
    await expectNothingDrawn(unauthenticated);
  });

  // 결정 4 B1: 202여도 본문 파싱·형식이 실패하면 공통 래퍼가 네트워크 오류(ApiError.network())로 던진다 → 무표시 경로.
  // 진짜 래퍼(live 모드, 가짜 fetch)로 보낸다. 최초 F-2가 대기 없음으로 멈춘 상태를 기준으로 이 응답 뒤 F-2가 다시 돌지 않는지 본다.
  async function expectMalformed202Ignored(make202: () => Response) {
    const x = freshId();
    const { store, loadPlan, planGets } = calculatedStore(12, x);
    const held = deferred<Response>();
    const calls: Array<{ method: string; path: string }> = [];
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    const fakeFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const method = init?.method ?? "GET";
      calls.push({ method, path });
      if (method === "POST" && path === F3_PATH) return held.promise;
      if (method === "GET" && path.startsWith("/api/chat/messages")) return json(200, chatPage([], false));
      throw new Error(`스크립트에 없는 호출: ${method} ${path}`);
    }) as typeof fetch;
    const polling = createPollingRegistry();
    const navigations: string[] = [];
    const client = createApiClient({
      mode: "live",
      fetch: fakeFetch,
      planStore: { clear: () => store.clear(), loadPlan: (options) => store.loadPlan(options) },
      polling,
      navigate: (to) => navigations.push(to),
      getPathname: () => "/result",
    });
    render(<ChatWindow deps={{ api: client as any, polling, planStore: store as any }} />);
    await flush();
    const f2 = () => calls.filter((c) => c.method === "GET" && c.path.startsWith("/api/chat/messages"));
    const f3 = () => calls.filter((c) => c.method === "POST" && c.path === F3_PATH);
    expect(f3()).toHaveLength(1);
    await tick(3000);
    expect(f2()).toHaveLength(1); // 최초 복원 뒤 멈춰 있다
    const before = chatMarkup();

    await act(async () => held.resolve(make202()));
    await flush();
    await tick(10_000);
    expect(f2()).toHaveLength(1); // 이 응답으로 폴링을 재개하지 않는다
    expect(f3()).toHaveLength(1); // 다시 보내지 않는다
    expect(loadPlan).not.toHaveBeenCalled();
    expect(planGets()).toHaveLength(0);
    expect(chatMarkup()).toBe(before);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    expect(navigations).toEqual([]);
  }

  it("202인데 본문이 비었으면(래퍼의 네트워크 오류) 아무것도 그리지 않고 폴링을 재개하지 않으며 다시 불러오거나 다시 보내지 않는다", async () => {
    await expectMalformed202Ignored(() => new Response(null, { status: 202, headers: { "content-type": "application/json" } }));
  });

  it("202인데 본문이 잘못된 JSON이면(래퍼의 네트워크 오류) 아무것도 그리지 않고 폴링을 재개하지 않으며 다시 불러오거나 다시 보내지 않는다", async () => {
    await expectMalformed202Ignored(() => new Response("{", { status: 202, headers: { "content-type": "application/json" } }));
  });

  it("202인데 Content-Type이 JSON이 아니면(래퍼의 네트워크 오류) 아무것도 그리지 않고 폴링을 재개하지 않으며 다시 불러오거나 다시 보내지 않는다", async () => {
    await expectMalformed202Ignored(() => new Response("accepted", { status: 202, headers: { "content-type": "text/plain" } }));
  });
});

describe("AC-10·AC-11 초과 ↔ 미달 전환 (D-063, D-072, FR-AI-05)", () => {
  it("초과 동안 버튼·F-3이 없고, revision이 바뀌어 초과가 풀리면 새 calcRunId로 F-3을 한 번 보내고 버튼이 보이며, 다시 초과가 되면 보내지 않고 버튼이 숨는다", async () => {
    const [z0, y, z1, w] = [freshId(), freshId(), freshId(), freshId()];
    const { store } = makeStore();
    store.acceptPlanView(view(12, "ALREADY_EXCEEDED", z0));
    const { f3Bodies } = await mountChat(store);
    await tick(3000);
    expect(f3Bodies()).toEqual([]);
    expect(retryButton()).toBeNull();

    act(() => store.acceptRevision(13)); // 입력 수정·되돌리기 응답: view는 아직 옛 값
    await flush();
    expect(f3Bodies()).toEqual([]);
    expect(retryButton()).toBeNull();

    act(() => store.acceptPlanView(view(13, "CALCULATED", y)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: y }]);
    expect(retryButton()).not.toBeNull();

    act(() => store.acceptRevision(14));
    act(() => store.acceptPlanView(view(14, "ALREADY_EXCEEDED", z1)));
    await tick(3000);
    expect(f3Bodies()).toEqual([{ calcRunId: y }]);
    expect(retryButton()).toBeNull();

    act(() => store.acceptPlanView(view(15, "CALCULATED", w)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: y }, { calcRunId: w }]);
    expect(retryButton()).not.toBeNull();
  });

  it("늦게 온 중간 revision의 초과 결과로는 버튼이 숨지 않고 F-3도 보내지 않는다: 최신 revision의 결과가 와야 판단한다 (settledStatus, C12a-6-7)", async () => {
    const [x, z, w] = [freshId(), freshId(), freshId()];
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
    expect(retryButton()).not.toBeNull();

    act(() => store.acceptRevision(14)); // 쓰기 응답으로 revision만 먼저 14
    await flush();
    expect(retryButton()).not.toBeNull();

    act(() => store.acceptPlanView(view(13, "ALREADY_EXCEEDED", z))); // 중간 revision 13의 늦은 초과 결과
    await tick(3000);
    expect(store.getState().revision).toBe(14);
    expect(store.getState().view?.calcRunId).toBe(z);
    expect(store.getState().settledStatus).toBe("CALCULATED"); // 직전 값 유지
    expect(retryButton()).not.toBeNull(); // view.status가 아니라 settledStatus로 그린다
    expect(f3Bodies()).toEqual([{ calcRunId: x }]); // 초과 view로는 보내지 않는다

    act(() => store.acceptPlanView(view(14, "CALCULATED", w))); // 최신 revision의 결과
    await flush();
    expect(retryButton()).not.toBeNull();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: w }]);
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: w }]);
  });

  it("초과 상태에서 revision만 먼저 오른 뒤 중간 revision의 CALCULATED 결과가 오면 F-3은 view 기준으로 그 calcRunId로 한 번 보내고, 버튼은 settledStatus 기준으로 숨긴 채 둔다 (검토 plan-7, C12a-6-7)", async () => {
    const [z, y, w] = [freshId(), freshId(), freshId()];
    const { store } = makeStore();
    store.acceptPlanView(view(12, "ALREADY_EXCEEDED", z));
    const { f3Bodies } = await mountChat(store);
    await tick(3000);
    expect(f3Bodies()).toEqual([]);
    expect(retryButton()).toBeNull();

    act(() => store.acceptRevision(14)); // 쓰기 응답으로 revision만 먼저 14
    await flush();
    act(() => store.acceptPlanView(view(13, "CALCULATED", y))); // 중간 revision 13의 CALCULATED 결과
    await flush();
    expect(store.getState().view?.calcRunId).toBe(y);
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED"); // 직전 값 유지
    expect(f3Bodies()).toEqual([{ calcRunId: y }]); // F-3 조건은 view.calcRunId 변화 + view.status(C12b-3a)
    expect(retryButton()).toBeNull(); // 버튼은 settledStatus(D-072)
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: y }]);

    act(() => store.acceptPlanView(view(14, "CALCULATED", w)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: y }, { calcRunId: w }]);
    expect(retryButton()).not.toBeNull();
  });
});
