// @vitest-environment jsdom
// Issue #52 AC-14~AC-18: 초과 계획 고정 문장(D-063)과 revision 변경 때의 재판단(D-072, settledStatus 읽기).
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planView } from "../../../api/__tests__/helpers";
import { ChatWindow } from "../index";
import {
  copy,
  doneMsg,
  flush,
  makeDeps,
  makeFakeApi,
  makePlanStore,
  makeReadOnlyStore,
  page,
  tick,
  userMsg,
} from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const fixed = () => screen.queryAllByTestId("chat-already-exceeded");
const retryButton = () => screen.queryByTestId("chat-retry-explanation");

async function mount(planStore: any, replies: Parameters<typeof makeFakeApi>[0] = { get: [page([], false)] }) {
  const api = makeFakeApi(replies);
  const { deps } = makeDeps(api, planStore);
  render(<ChatWindow deps={deps} />);
  await flush();
  return { api };
}

const exceededStore = (revision = 12) => {
  const store = makePlanStore();
  store.acceptPlanView(planView(revision, "ALREADY_EXCEEDED"));
  return store;
};

const calculatedStore = (revision = 12) => {
  const store = makePlanStore();
  store.acceptPlanView(planView(revision, "CALCULATED"));
  return store;
};

describe("AC-14 초과 계획에서 대화창을 열면 (D-063)", () => {
  it("대화창은 열려 있고(질문을 보낼 수 있음) 문구표 chat.alreadyExceeded 문장이 한 줄 있다", async () => {
    const { api } = await mount(exceededStore(), { get: [page([], false)], post: [{ userMessageId: 1030, assistantMessageId: 1031 }] });
    expect(fixed()).toHaveLength(1);
    expect(fixed()[0].textContent).toBe(copy["chat.alreadyExceeded"]);

    // 질문은 평소처럼 받는다
    const input = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: "월 50만원으로 하면 어때요?" } });
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByTestId("chat-send"));
    await flush();
    expect(api.posts()).toHaveLength(1);
    expect(api.posts()[0]).toMatchObject({ path: "/api/chat/messages", body: { text: "월 50만원으로 하면 어때요?" } });
  });

  it("이 문장은 프론트가 그린다: F-3·LLM 호출이 없고, 메시지 목록의 일반 말풍선과 구분되며 맨 위(목록 앞)에 있다", async () => {
    const { api } = await mount(exceededStore(), {
      get: [page([userMsg(1030), doneMsg(1031, "ANSWER", "답변")], false)],
    });
    expect(api.posts()).toHaveLength(0);
    expect(api.calls.some((c) => c.path.includes("/api/chat/explanations"))).toBe(false);

    const sentence = fixed()[0];
    const list = screen.getByTestId("chat-messages");
    expect(sentence.closest('[data-testid="chat-message"]')).toBeNull();
    expect(list.contains(sentence)).toBe(false);
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(2);
    // 목록과 입력창보다 앞에 있다
    expect(sentence.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(sentence.compareDocumentPosition(screen.getByTestId("chat-input")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("대화창 초과 문장은 결과 화면 초과 안내(result.alreadyExceeded)와 다른 키·다른 문장이다", async () => {
    expect(copy["chat.alreadyExceeded"]).toBeTruthy();
    expect(copy["result.alreadyExceeded"]).toBeTruthy();
    expect(copy["chat.alreadyExceeded"]).not.toBe(copy["result.alreadyExceeded"]);
    await mount(exceededStore());
    expect(fixed()[0].textContent).not.toContain("{heldAssets}");
    expect(fixed()[0].textContent).toBe(copy["chat.alreadyExceeded"]);
  });
});

describe("AC-15 설명 다시 요청 버튼", () => {
  it("초과 계획에서는 그리지 않는다", async () => {
    await mount(exceededStore());
    expect(retryButton()).toBeNull();
  });

  it("미달·달성 결과에서는 고정 문장이 없고 설명 다시 요청 버튼이 보인다", async () => {
    await mount(calculatedStore());
    expect(fixed()).toHaveLength(0);
    expect(retryButton()).not.toBeNull();
  });
});

describe("AC-16 revision이 바뀌어 초과가 됨 (D-072)", () => {
  it("settledStatus가 ALREADY_EXCEEDED로 바뀌면 맨 위 고정 문장이 나타나고 버튼이 숨는다", async () => {
    const store = calculatedStore(12);
    await mount(store);
    expect(fixed()).toHaveLength(0);
    expect(retryButton()).not.toBeNull();

    act(() => {
      store.acceptRevision(13); // 대화 적용 응답({revision})
    });
    act(() => {
      store.acceptPlanView(planView(13, "ALREADY_EXCEEDED")); // loadPlan() 응답
    });
    expect(fixed()).toHaveLength(1);
    expect(fixed()[0].textContent).toBe(copy["chat.alreadyExceeded"]);
    expect(retryButton()).toBeNull();
  });
});

describe("AC-17 초과가 풀림 (D-072)", () => {
  it("settledStatus가 CALCULATED가 되면 문장이 지워지고 버튼이 다시 보이며, 고지는 그대로다", async () => {
    const store = exceededStore(12);
    await mount(store);
    expect(fixed()).toHaveLength(1);
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);

    act(() => {
      store.acceptRevision(14); // 되돌리기 응답
    });
    act(() => {
      store.acceptPlanView(planView(14, "CALCULATED"));
    });
    expect(fixed()).toHaveLength(0);
    expect(retryButton()).not.toBeNull();
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
  });

  it("초과 여부와 무관하게 고지 한 줄은 항상 같다", async () => {
    const store = calculatedStore(12);
    await mount(store);
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
    act(() => {
      store.acceptPlanView(planView(13, "ALREADY_EXCEEDED"));
    });
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);
  });

  it("최초 로드는 현재 status만 보고 그린다(미달로 시작하면 문장 없음, 초과로 시작하면 문장 있음)", async () => {
    await mount(calculatedStore());
    expect(fixed()).toHaveLength(0);
    cleanup();
    await mount(exceededStore());
    expect(fixed()).toHaveLength(1);
  });
});

describe("AC-18 revision만 먼저 바뀌고 view는 옛 값일 때 (D-072, C12a-6-7, J-12f)", () => {
  it("초과였던 계획: F-5·P-5 응답으로 revision만 바뀌어도 옛 status로 문장을 지우지 않고, 새 view가 온 뒤에만 바뀐다", async () => {
    const store = exceededStore(12);
    await mount(store);
    act(() => {
      store.acceptRevision(13);
    });
    expect(store.getState().revision).toBe(13);
    expect(store.getState().view?.revision).toBe(12);
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED"); // 직전 값 유지
    expect(fixed()).toHaveLength(1);
    expect(retryButton()).toBeNull();

    act(() => {
      store.acceptPlanView(planView(13, "CALCULATED"));
    });
    expect(fixed()).toHaveLength(0);
    expect(retryButton()).not.toBeNull();
  });

  it("미달이던 계획: revision만 바뀌었을 때 문장을 만들지 않는다", async () => {
    const store = calculatedStore(12);
    await mount(store);
    act(() => {
      store.acceptRevision(13);
    });
    expect(fixed()).toHaveLength(0);
    expect(retryButton()).not.toBeNull();
    act(() => {
      store.acceptPlanView(planView(13, "ALREADY_EXCEEDED"));
    });
    expect(fixed()).toHaveLength(1);
  });

  it("settledStatus가 null(아직 모름)이면 고정 문장을 그리지 않는다", async () => {
    await mount(makePlanStore());
    expect(fixed()).toHaveLength(0);
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);
  });

  it("store의 getState·subscribe만 읽는다: 그 두 함수만 가진 store로도 settledStatus 변화를 따라간다(#53의 revision 구독을 쓰지 않음)", async () => {
    const store = makeReadOnlyStore({ revision: 12, settledStatus: "CALCULATED" });
    await mount(store);
    expect(fixed()).toHaveLength(0);

    act(() => store.set({ revision: 13 })); // revision만 바뀜: 판단을 바꾸지 않는다
    expect(fixed()).toHaveLength(0);
    expect(retryButton()).not.toBeNull();

    act(() => store.set({ settledStatus: "ALREADY_EXCEEDED" }));
    expect(fixed()).toHaveLength(1);
    expect(retryButton()).toBeNull();

    act(() => store.set({ revision: 14 }));
    expect(fixed()).toHaveLength(1);

    act(() => store.set({ settledStatus: "CALCULATED" }));
    expect(fixed()).toHaveLength(0);
  });

  it("초과 문장이 있어도 폴링·메시지 그리기는 그대로 돈다", async () => {
    const { api } = await mount(exceededStore(), {
      get: [page([userMsg(1030), doneMsg(1031, "ANSWER", "답")], false)],
    });
    await tick(5000);
    expect(api.gets()).toHaveLength(1);
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(2);
    expect(fixed()).toHaveLength(1);
  });
});
