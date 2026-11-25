// @vitest-environment jsdom
// #400: 대화창 ETF 버튼은 질문만 보낸다. 계획은 AI 비교 카드의 "내 계획으로 적용"을 눌러야 바뀐다.
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { doneMsg, flush, page, tick, userMsg } from "../../__tests__/helpers";
import { buttonOf, comparison, makeApi, makeStore, mountChat, planView } from "./helpers";

const PRODUCTS = {
  products: [
    { code: "SP500", displayName: "S&P500 추종 ETF", gainTaxRatePct: 15.4 },
    { code: "133690", displayName: "나스닥100 추종 ETF", gainTaxRatePct: 15.4 },
  ],
};

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function etfPlan(revision: number) {
  const view = planView(revision);
  view.result.assumptions.etfProductCode = "133690";
  return view;
}

function mount(replies: Record<string, unknown[]> = {}) {
  const api = makeApi({
    "GET /api/etf-products": [PRODUCTS],
    F2: [page([], false)],
    "POST /api/chat/messages": [{ userMessageId: 1030, assistantMessageId: 1031 }],
    ...replies,
  });
  const env = makeStore({ initialRevision: 12, planReplies: [etfPlan(13)] });
  env.store.acceptPlanView(etfPlan(12));
  return { api, ...env };
}

const chip = () => screen.getAllByTestId("chat-etf-chip")[0] as HTMLButtonElement;
const planApplyCalls = (api: ReturnType<typeof makeApi>) => api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/apply");

describe("ETF 버튼 = 질문만", () => {
  it("버튼을 눌러도 계획은 바로 바뀌지 않는다(질문 1건만 가고 적용 호출·revision 변화 없음)", async () => {
    const { api, store } = mount();
    await mountChat(api, store);

    fireEvent.click(chip());
    await flush();
    await flush();

    expect(api.calls.filter((c) => c.method === "POST" && c.path === "/api/chat/messages")).toHaveLength(1);
    expect(planApplyCalls(api)).toHaveLength(0);
    expect(api.f5Calls()).toHaveLength(0);
    expect(store.getState().revision).toBe(12);
  });

  it("AI 비교 카드의 '내 계획으로 적용'을 누르면 그때 계획이 바뀐다(F-5)", async () => {
    const { api, store } = mount({
      F2: [page([], false), page([userMsg(1030), doneMsg(1031, "RECALC_RESULT", "S&P500으로 바꿔 보면 평균이 줄어요")], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
      "F5 1031": [{ revision: 13 }],
    });
    await mountChat(api, store);

    fireEvent.click(chip());
    await flush();
    await tick(5000);
    await flush();
    await flush();
    expect(store.getState().revision).toBe(12);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();

    expect(api.f5Calls()).toHaveLength(1);
    expect(store.getState().view?.revision).toBe(13);
  });
});
