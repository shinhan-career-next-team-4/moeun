// @vitest-environment jsdom
// Issue #53 AC-1·AC-4·AC-6~AC-10: 적용 버튼 활성 규칙(C12b-6 "버튼 활성 한 줄", D-041, D-069)과 F-4 호출 규칙.
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readMock } from "../../../../api/__tests__/helpers";
import { copy, doneMsg, flush, page, pendingMsg, problem, serverError, tick, userMsg } from "../../__tests__/helpers";
import {
  APPLY_LABEL,
  COLLAPSED_LABEL,
  applyButton,
  buttonOf,
  cardOf,
  applyButtons,
  bubble,
  card,
  cards,
  comparison,
  conditionRows,
  deferred,
  failedNotice,
  makeApi,
  makeStore,
  mountChat,
  planView,
  scenarioRows,
} from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number, content = "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요") =>
  doneMsg(id, "RECALC_RESULT", content);
const f4Path = (id: number) => `/api/chat/messages/${id}/comparison`;

/** 질문을 보내 새 RECALC_RESULT(assistantId)를 폴링으로 받는다. */
async function askAndReceive(questionId: number, assistantId: number) {
  fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "기간을 5년으로 하면요?" } });
  fireEvent.click(screen.getByTestId("chat-send"));
  await flush();
  await tick(1000);
  await flush();
  expect(bubble(questionId)).not.toBeNull();
  expect(bubble(assistantId)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
}

describe("AC-1 비교 결과가 두 개면 마지막 카드만 활성 (D-041, FR-AI-07)", () => {
  it("첫 번째 카드는 '이전 결과'로 접히고 적용 버튼이 비활성, 마지막 카드만 활성이다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    });
    const { store } = makeStore();
    await mountChat(api, store);

    expect(cards().map((el) => el.getAttribute("data-message-id"))).toEqual(["1029", "1031"]);
    expect(cardOf(1029).getAttribute("data-collapsed")).toBe("true");
    expect(cardOf(1029).textContent).toContain(COLLAPSED_LABEL);
    expect(buttonOf(1029).disabled).toBe(true);

    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("false");
    expect(buttonOf(1031).disabled).toBe(false);
    expect(buttonOf(1031).textContent).toContain(APPLY_LABEL);
  });
});

describe("AC-4 적용 후 되돌리면 이전 카드의 적용 버튼이 모두 비활성 (FR-PLAN-04, D-069)", () => {
  it("적용 성공(revision 13) 뒤 되돌리기 성공(revision 14)이 store에 반영되면 모든 버튼이 비활성이다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
    });
    const { store } = makeStore({ planReplies: [planView(13)] });
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    expect(store.getState().revision).toBe(13);

    // 되돌리기(#51, A팀 코드)는 store의 revision만 바꾼다. 대화창은 그 칸만 본다.
    act(() => store.acceptRevision(14));
    await flush();
    expect(applyButtons().length).toBeGreaterThanOrEqual(2);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(buttonOf(1029).disabled).toBe(true);
    expect(buttonOf(1031).disabled).toBe(true);
  });
});

describe("AC-6 초과 계획에서 대화로 조건을 바꾸면 ALREADY_EXCEEDED 고정 문장(ANSWER)만 온다 (D-071)", () => {
  it("(회귀 보호) ANSWER 메시지에는 F-4를 부르지 않고 비교 카드·적용 버튼이 없다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030, "월 50만원으로 하면 어때요?"), doneMsg(1031, "ANSWER", copy["chat.alreadyExceeded"])], false)],
    });
    const { store } = makeStore({ initialRevision: null });
    store.acceptPlanView(planView(12, "ALREADY_EXCEEDED"));
    await mountChat(api, store);
    await tick(3000);

    expect(bubble(1031)?.textContent).toContain(copy["chat.alreadyExceeded"]);
    expect(api.f4Calls()).toEqual([]);
    expect(cards()).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
    expect(screen.queryByText(APPLY_LABEL)).toBeNull();
  });

  it("(회귀 보호) 질문을 보내고 PENDING이던 답이 폴링으로 ALREADY_EXCEEDED 고정 문장(ANSWER)으로 완료되면 F-4를 부르지 않고 카드·버튼이 없다", async () => {
    const api = makeApi({
      F2: [page([], false), page([userMsg(1030, "월 50만원으로 하면 어때요?"), doneMsg(1031, "ANSWER", copy["chat.alreadyExceeded"])], false)],
      "POST /api/chat/messages": [{ userMessageId: 1030, assistantMessageId: 1031 }],
    });
    const { store } = makeStore({ initialRevision: null });
    store.acceptPlanView(planView(12, "ALREADY_EXCEEDED"));
    await mountChat(api, store);
    expect(api.f4Calls()).toEqual([]);

    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "월 50만원으로 하면 어때요?" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await flush();
    expect(api.calls.filter((c) => c.method === "POST" && c.path === "/api/chat/messages")).toHaveLength(1);
    await tick(1000); // 폴링: 1031 PENDING -> ANSWER 완료
    await flush();
    await flush();
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("ANSWER");
    expect(bubble(1031)?.textContent).toContain(copy["chat.alreadyExceeded"]);
    await tick(3000);
    await flush();

    expect(api.f4Calls(), "폴링으로 완료된 ANSWER에 F-4를 불렀다").toEqual([]);
    expect(cards()).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
    expect(screen.queryByText(APPLY_LABEL)).toBeNull();
  });
});

describe("AC-7 복원으로 RECALC_RESULT가 여러 개 오면 (H-11b, C12b-6)", () => {
  it("get-messages.200-restore-history: F-4는 가장 최근(1031)에만 1회, 이전(1027·1029)은 본문만 보이고 '이전 결과'로 접혀 버튼이 비활성이다", async () => {
    const restore = readMock("get-messages.200-restore-history.json").responses[0].body;
    const api = makeApi({
      F2: [restore],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    });
    const { store } = makeStore();
    await mountChat(api, store);

    expect(api.f4Calls()).toEqual([f4Path(1031)]);
    for (const id of [1027, 1029]) {
      const message = restore.messages.find((m: { id: number }) => m.id === id);
      expect(bubble(id)?.textContent).toContain(message.content);
      expect(card(id)!.getAttribute("data-collapsed")).toBe("true");
      expect(card(id)!.textContent).toContain(COLLAPSED_LABEL);
      expect(conditionRows(id)).toHaveLength(0);
      expect(applyButton(id)!.disabled).toBe(true);
    }
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("false");
    expect(buttonOf(1031).disabled).toBe(false);
  });

  it("이미 받은 비교는 다시 부르지 않는다: 시간이 지나고 store가 다시 그려져도 F-4는 1회다", async () => {
    const restore = readMock("get-messages.200-restore-history.json").responses[0].body;
    const api = makeApi({
      F2: [restore],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    });
    const { store } = makeStore({ planReplies: [planView(12)] });
    await mountChat(api, store);
    await tick(10_000);
    act(() => store.acceptPlanView(planView(12))); // 같은 revision 재조회(바뀜 아님)
    await tick(10_000);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
    expect(buttonOf(1031).disabled).toBe(false);
  });
});

describe("AC-8 새 RECALC_RESULT가 폴링으로 도착하면 (H-11b, C12b-6)", () => {
  it("이전 카드 버튼이 새 카드의 F-4 응답을 기다리지 않고 즉시 비활성이 되고, 이전 카드의 F-4는 다시 부르지 않는다", async () => {
    const f4New = deferred<unknown>();
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033, "기간을 5년으로 바꾸면 평균일 때 달성해요")], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [f4New.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착, 그 F-4는 아직 응답 전
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(buttonOf(1031).disabled).toBe(true);
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);

    f4New.resolve(comparison("get-comparison.200.json", { messageId: 1033 }));
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
  });

  it("이미 받은 1031의 상세(조건·시나리오)는 1033 도착 즉시 접히고 본문만 남는다: 1033의 F-4 응답 전에도, 성공 뒤에도 1031 상세가 보이지 않는다", async () => {
    const body1031 = comparison("get-comparison.200.json", { messageId: 1031 });
    const content1031 = "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요";
    const f4New = deferred<unknown>();
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031, content1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033, "기간을 5년으로 바꾸면 평균일 때 달성해요")], false),
      ],
      "F4 1031": [body1031],
      "F4 1033": [f4New.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    // 처음에는 1031이 최신이라 상세가 펼쳐져 있다
    expect(conditionRows(1031)).toHaveLength(1); // 바뀐 조건만(#417)
    expect(scenarioRows(1031).length).toBeGreaterThan(0);
    const display = body1031.display as Record<string, string>;
    const detailValues = Object.values(display).filter((value) => value.trim().length >= 2);
    expect(detailValues.length).toBeGreaterThan(0);

    /** 카드 안의 글자(카드가 말풍선을 감싸더라도 말풍선 본문은 뺀다). */
    const cardOwnText = (id: number) => {
      const clone = cardOf(id).cloneNode(true) as HTMLElement;
      for (const el of Array.from(clone.querySelectorAll('[data-testid="chat-message"]'))) el.remove();
      return clone.textContent ?? "";
    };
    const expectCollapsedDetail = () => {
      expect(bubble(1031)?.textContent).toContain(content1031);
      expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
      expect(cardOf(1031).textContent).toContain(COLLAPSED_LABEL);
      expect(conditionRows(1031), "접힌 1031에 조건 행이 남아 있다").toHaveLength(0);
      expect(scenarioRows(1031), "접힌 1031에 시나리오 행이 남아 있다").toHaveLength(0);
      const text = cardOwnText(1031);
      for (const value of detailValues) expect(text, `접힌 1031 카드에 display 값 "${value}"가 보인다`).not.toContain(value);
      expect(applyButton(1031)?.disabled ?? true).toBe(true);
    };

    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착, 그 F-4는 아직 응답 전
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expectCollapsedDetail();
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);

    f4New.resolve(comparison("get-comparison.200.json", { messageId: 1033, applicable: true }));
    await flush();
    await flush();
    expect(conditionRows(1033)).toHaveLength(1); // 바뀐 조건만(#417)
    expect(buttonOf(1033).disabled).toBe(false);
    expectCollapsedDetail();
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
  });

  it("두 F-4 응답이 역순으로 와도(1033 먼저, 1031 나중) 최대 id인 1033만 활성이고 1031은 접힌 비활성으로 남는다", async () => {
    const f4Old = deferred<unknown>();
    const f4New = deferred<unknown>();
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033, "기간을 5년으로 바꾸면 평균일 때 달성해요")], false),
      ],
      "F4 1031": [f4Old.promise],
      "F4 1033": [f4New.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);

    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착. 두 F-4 모두 응답 전
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);

    f4New.resolve(comparison("get-comparison.200.json", { messageId: 1033, applicable: true }));
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
    expect(applyButtons().filter((button) => !button.disabled)).toEqual([buttonOf(1033)]);

    f4Old.resolve(comparison("get-comparison.200.json", { messageId: 1031, applicable: true }));
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
    expect(applyButton(1031)?.disabled ?? true).toBe(true);
    expect(applyButtons().filter((button) => !button.disabled)).toEqual([buttonOf(1033)]);
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
  });

  it("더 작은 id(1031)가 나중에 RECALC_RESULT로 완료되어도 최대 id인 1033만 활성이고 1031은 접히며 1031의 F-4는 부르지 않는다", async () => {
    const api = makeApi({
      F2: [
        page([userMsg(1030), pendingMsg(1031), userMsg(1032), recalc(1033, "기간을 5년으로 바꾸면 평균일 때 달성해요")], true),
        page([recalc(1031)], false),
      ],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1033).disabled).toBe(false);
    expect(api.f4Calls()).toEqual([f4Path(1033)]);

    await tick(1000); // 폴링: 1031이 RECALC_RESULT로 늦게 완료
    await flush();
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(buttonOf(1033).disabled).toBe(false);
    expect(cardOf(1033).getAttribute("data-collapsed")).toBe("false");
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
    expect(applyButton(1031)?.disabled ?? true).toBe(true);
    expect(applyButtons().filter((button) => !button.disabled)).toEqual([buttonOf(1033)]);
    expect(api.f4Calls()).toEqual([f4Path(1033)]);
  });

  // 새 최신 비교의 F-4가 실패해도 '최대 RECALC_RESULT id' 조건은 그대로다: 이전 카드(1031)를 다시 켜지 않는다(P-1, P-5).
  async function newComparisonFails(f4Error: unknown) {
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033, "기간을 5년으로 바꾸면 평균일 때 달성해요")], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
      "F4 1033": [f4Error],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착, 그 F-4는 실패
    await flush();
    await flush();
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    await tick(30_000);
    await flush();
    expect(applyButton(1031)?.disabled ?? true, "새 비교의 F-4 실패 뒤 1031 버튼이 다시 활성이 됐다").toBe(true);
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("true");
    expect(applyButtons().filter((button) => !button.disabled)).toHaveLength(0);
    expect(applyButton(1033)).toBeNull();
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
  }

  it("새 비교(1033)의 F-4가 500이면 1033 자리에 실패 안내가 보이고, 이전 카드 1031은 다시 활성이 되지 않아 활성 버튼이 0개다", async () => {
    await newComparisonFails(serverError());
    expect(failedNotice(1033)?.textContent).toContain(copy["comparison.loadFailed"]);
  });

  it("새 비교(1033)의 F-4가 404면 1033 자리는 비고, 이전 카드 1031은 다시 활성이 되지 않아 활성 버튼이 0개다", async () => {
    await newComparisonFails(problem(404, "NOT_FOUND"));
    expect(card(1033)).toBeNull();
    expect(failedNotice(1033)).toBeNull();
  });

  it("get-comparison.200-not-applicable(1027): 최대 RECALC_RESULT여도 applicable=false면 버튼이 비활성이다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1026), recalc(1027)], false)],
      "F4 1027": [comparison("get-comparison.200-not-applicable.json")],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(cardOf(1027).getAttribute("data-collapsed")).toBe("false");
    expect(buttonOf(1027).disabled).toBe(true);
  });

  it("applicable이 불리언 true가 아니면(키 누락·null·문자열 \"false\"·문자열 \"true\"·숫자 1) 최신 비교라도 활성 버튼이 없고 눌러도 F-5를 보내지 않는다(잠정 결정 P-56)", async () => {
    const cases: Array<[string, (body: Record<string, unknown>) => void]> = [
      ["키 누락", (body) => delete body.applicable],
      ["null", (body) => (body.applicable = null)],
      ['문자열 "false"', (body) => (body.applicable = "false")],
      ['문자열 "true"', (body) => (body.applicable = "true")],
      ["숫자 1", (body) => (body.applicable = 1)],
    ];
    const mountWithApplicable = async (patch: ((body: Record<string, unknown>) => void) | null) => {
      const body = comparison("get-comparison.200.json", { messageId: 1031 }) as Record<string, unknown>;
      if (patch) patch(body);
      else body.applicable = true;
      const api = makeApi({
        F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
        "F4 1031": [body],
        "F5 1031": [{ revision: 13 }],
      });
      const { store } = makeStore();
      await mountChat(api, store);
      return { api, store };
    };

    // 대조: 불리언 true면 활성이다
    await mountWithApplicable(null);
    expect(buttonOf(1031).disabled).toBe(false);
    cleanup();

    for (const [name, patch] of cases) {
      const { api, store } = await mountWithApplicable(patch);
      await tick(1000);
      await flush();
      expect(applyButtons().filter((button) => !button.disabled), `applicable ${name}인데 활성 버튼이 있다`).toHaveLength(0);
      for (const button of applyButtons()) fireEvent.click(button);
      await flush();
      await flush();
      expect(api.f5Calls(), `applicable ${name}인데 F-5를 보냈다`).toHaveLength(0);
      expect(store.getState().revision).toBe(12);
      expect(api.f4Calls()).toEqual([f4Path(1031)]);
      cleanup();
    }
  });
});

describe("AC-9 공유 store의 revision 칸이 바뀌면 그때 가진 모든 적용 버튼을 끈다 (D-069, C12b-7-4)", () => {
  const triggers: Array<[string, (store: ReturnType<typeof makeStore>["store"]) => void]> = [
    ["대화 적용 성공·되돌리기 성공(F-5·P-5 200 revision)", (store) => store.acceptRevision(13)],
    ["입력 수정 저장(P-3 200 PlanView)", (store) => store.acceptPlanView(planView(13))],
    ["PLAN_CHANGED 뒤 다시 불러옴(P-4 200 PlanView)", (store) => store.acceptPlanView(planView(14))],
  ];

  it.each(triggers)("%s: 활성이던 버튼이 F-4 재호출 없이 비활성이 된다", async (_name, change) => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    act(() => change(store));
    await flush();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });

  it("그 뒤 새로 온 비교는 F-4 applicable을 따른다(true면 활성)", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false), page([userMsg(1032), recalc(1033)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "POST /api/chat/messages": [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    act(() => store.acceptRevision(13));
    await flush();
    expect(buttonOf(1031).disabled).toBe(true);

    await askAndReceive(1032, 1033);
    expect(buttonOf(1033).disabled).toBe(false);
    expect(buttonOf(1031).disabled).toBe(true);
  });

  // 구독은 첫 변경 한 번이 아니라 매번 반응한다: 두 번째 변경 때 그 사이 도착해 활성이던 비교도 끈다.
  async function secondChangeDisablesInterimComparison(secondChange: (store: ReturnType<typeof makeStore>["store"]) => void) {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false), page([userMsg(1032), recalc(1033)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "POST /api/chat/messages": [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);
    act(() => store.acceptRevision(13)); // 첫 번째 변경
    await flush();
    expect(buttonOf(1031).disabled).toBe(true);

    await askAndReceive(1032, 1033);
    expect(buttonOf(1033).disabled).toBe(false);

    act(() => secondChange(store)); // 두 번째 변경
    await flush();
    expect(buttonOf(1033).disabled, "두 번째 revision 변경 뒤에도 1033이 활성이다").toBe(true);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
  }

  it("revision이 두 번째로 바뀌면(13 -> 14, acceptRevision) 그 사이 도착해 활성이던 새 비교(1033)도 비활성이 되고 F-4는 다시 부르지 않는다", async () => {
    await secondChangeDisablesInterimComparison((store) => store.acceptRevision(14));
  });

  it("revision이 두 번째로 바뀌면(13 -> 14, acceptPlanView) 그 사이 도착해 활성이던 새 비교(1033)도 비활성이 되고 F-4는 다시 부르지 않는다", async () => {
    await secondChangeDisablesInterimComparison((store) => store.acceptPlanView(planView(14)));
  });

  it("그 뒤 새로 온 비교라도 F-4 applicable=false면 비활성이다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false), page([userMsg(1032), recalc(1033)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: false })],
      "POST /api/chat/messages": [{ userMessageId: 1032, assistantMessageId: 1033 }],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    act(() => store.acceptRevision(13));
    await flush();

    await askAndReceive(1032, 1033);
    expect(buttonOf(1033).disabled).toBe(true);
  });

  // 끈 상태는 메모리에만 있다. 새로고침(대화창·store를 새로 만듦) 뒤에는 서버 F-4 applicable이 판단한다(D-068, C12b-7-4).
  async function disableThenReload(applicableAfterReload: boolean) {
    const storageBefore = { local: localStorage.length, session: sessionStorage.length };
    const first = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const before = makeStore();
    await mountChat(first, before.store);
    act(() => before.store.acceptRevision(13));
    await flush();
    expect(buttonOf(1031).disabled).toBe(true);
    expect({ local: localStorage.length, session: sessionStorage.length }).toEqual(storageBefore);

    cleanup(); // 새로고침: 새 store가 서버에서 revision 13을 처음 받는다(최초 로드)
    const second = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: applicableAfterReload })],
    });
    const after = makeStore({ initialRevision: 13 });
    await mountChat(second, after.store);
    expect(second.f4Calls()).toEqual([f4Path(1031)]);
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("false");
    return buttonOf(1031);
  }

  it("끈 상태는 메모리에만 있다: 새로고침 뒤 서버 F-4가 applicable=true면 그 버튼은 활성이다(저장해 둔 비활성 상태가 없다)", async () => {
    expect((await disableThenReload(true)).disabled).toBe(false);
  });

  it("새로고침 뒤 서버 F-4가 applicable=false면 비활성이다(서버가 같은 결과를 낸다)", async () => {
    expect((await disableThenReload(false)).disabled).toBe(true);
  });

  it("revision이 값에서 비워진 뒤(12 -> null, NO_PLAN 수용) 같은 대화창에 새 계획이 처음 채워져도(null -> 1) 이전 비교 버튼은 비활성으로 남고 F-4를 다시 부르지 않는다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    act(() => {
      store.acceptNoPlan(12);
    });
    await flush();
    expect(store.getState().revision).toBeNull();
    expect(buttonOf(1031).disabled).toBe(true);

    act(() => store.acceptPlanView(planView(1))); // 입력 화면에서 새 계획 저장 뒤 첫 값
    await flush();
    await tick(1000);
    await flush();
    expect(store.getState().revision).toBe(1);
    expect(buttonOf(1031).disabled).toBe(true);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });
});

describe("AC-26 초과 계획(settledStatus=ALREADY_EXCEEDED)에서도 비교 카드의 적용 버튼은 보인다 (#382, 이전 D-072 숨김 결정을 사용자가 바꿈)", () => {
  it("초과가 아닌 계획(CALCULATED)이면 같은 카드의 적용 버튼이 있다(숨김은 초과일 때만)", async () => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(store.getState().settledStatus).toBe("CALCULATED");
    expect(buttonOf(1031).disabled).toBe(false);
  });

  it("revision 최초 로드(null -> 12)는 버튼을 끄지 않는다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    });
    const { store } = makeStore({ initialRevision: null });
    await mountChat(api, store);

    act(() => store.acceptPlanView(planView(12)));
    await flush();
    expect(store.getState().revision).toBe(12);
    expect(buttonOf(1031).disabled).toBe(false);
  });

  it("revision이 바뀌기 전에 보낸 F-4가 바뀐 뒤 applicable=true로 와도, 그 메시지가 이미 있었으면 비활성이다", async () => {
    const lateF4 = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [lateF4.promise],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);

    act(() => store.acceptRevision(13));
    await flush();
    lateF4.resolve(comparison("get-comparison.200.json", { messageId: 1031, applicable: true }));
    await flush();
    await flush();

    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("false");
    expect(buttonOf(1031).disabled).toBe(true);
  });

  it("계획이 ALREADY_EXCEEDED로 확정돼도 적용 버튼은 사라지지 않는다(목표 금액을 올리는 비교는 적용할 수 있다)", async () => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    act(() => store.acceptPlanView(planView(13, "ALREADY_EXCEEDED")));
    await flush();
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED");
    // 최신 비교 카드의 적용 버튼이 그대로 있다(계획이 바뀌었으니 비활성일 뿐 숨겨지지 않는다)
    expect(applyButton(1031)).not.toBeNull();
  });

  it("처음부터 초과 계획으로 열어도 복원된 비교 카드에 적용 버튼이 있다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const { store } = makeStore({ initialRevision: null });
    act(() => store.acceptPlanView(planView(12, "ALREADY_EXCEEDED")));
    await mountChat(api, store);
    await tick(1000);
    await flush();
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED");
    expect(buttonOf(1031).disabled).toBe(false);
  });
});

describe("AC-21 revision이 바뀔 때 PENDING이던 답이 뒤에 RECALC_RESULT로 완료되면 (사용자 결정 U-1=A)", () => {
  it("완료 시점을 '도착'으로 보고 F-4 applicable=true면 활성, 그 전에 있던 카드는 비활성이다", async () => {
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033, "기간을 5년으로 바꾸면 평균일 때 달성해요")], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    expect(buttonOf(1031).disabled).toBe(false);

    act(() => store.acceptRevision(13)); // 1033은 아직 PENDING(버튼 없음)
    await flush();
    expect(buttonOf(1031).disabled).toBe(true);

    await tick(1000); // 폴링: 1033이 RECALC_RESULT로 완료
    await flush();
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(buttonOf(1033).disabled).toBe(false);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(api.f4Calls()).toEqual([f4Path(1031), f4Path(1033)]);
  });
});

describe("AC-22 store revision이 아직 null이면 (사용자 결정 U-3=B)", () => {
  it("F-4 applicable=true여도 버튼은 비활성이고, revision이 채워지면(최초 로드) 활성이 된다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const { store } = makeStore({ initialRevision: null });
    await mountChat(api, store);
    expect(store.getState().revision).toBeNull();
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("false");
    expect(buttonOf(1031).disabled).toBe(true);

    act(() => store.acceptPlanView(planView(12)));
    await flush();
    expect(buttonOf(1031).disabled).toBe(false);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });
});

describe("AC-27 F-4 호출은 F-2 폴링(횟수·멈춤)을 바꾸지 않는다 (C12b-6, 사용자 결정 4차 B1=A)", () => {
  it("폴링으로 RECALC_RESULT가 완료되면 F-4를 1건 부르고, F-2 호출은 복원 1건·폴링 2건에서 멈춘 그대로다(F-4가 폴링을 다시 시작하거나 afterId를 바꾸지 않는다)", async () => {
    const api = makeApi({
      F2: [
        page([userMsg(1030), pendingMsg(1031)], true),
        page([pendingMsg(1031)], true),
        page([recalc(1031)], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
    });
    const { store } = makeStore();
    await mountChat(api, store);
    const f2Calls = () => api.calls.filter((c) => c.method === "GET" && c.path.split("?")[0] === "/api/chat/messages").map((c) => c.path);
    expect(api.f4Calls()).toEqual([]); // PENDING 동안 F-4 없음

    await tick(1000); // 폴링 1: PENDING 그대로
    await flush();
    expect(api.f4Calls()).toEqual([]);

    await tick(1000); // 폴링 2: 1031 RECALC_RESULT, hasPending=false
    await flush();
    await flush();
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
    expect(buttonOf(1031).disabled).toBe(false);

    await tick(10_000);
    await flush();
    expect(f2Calls()).toEqual(["/api/chat/messages", "/api/chat/messages?afterId=1030", "/api/chat/messages?afterId=1030"]);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });
});
