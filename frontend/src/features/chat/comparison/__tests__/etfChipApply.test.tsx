// @vitest-environment jsdom
// #372: 대화창 ETF 버튼은 질문이 접수되면 AI 답을 기다리지 않고 바로 같은 종목을 내 계획에 적용한다(P-6 COMPARE).
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, page } from "../../__tests__/helpers";
import { makeApi, makeStore, mountChat, planView } from "./helpers";

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
    "POST /api/plan/apply": [{ revision: 13 }],
    ...replies,
  });
  const env = makeStore({ initialRevision: 12, planReplies: [etfPlan(13)] });
  env.store.acceptPlanView(etfPlan(12));
  return { api, ...env };
}

const chip = () => screen.getAllByTestId("chat-etf-chip")[0] as HTMLButtonElement;
const applyCalls = (api: ReturnType<typeof makeApi>) => api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/apply");

describe("ETF 버튼 = 질문 + 바로 적용", () => {
  it("버튼을 누르면 질문이 가고, AI 답이 오기 전에 종목만 P-6으로 적용되어 revision이 바뀐다", async () => {
    const { api, store } = mount();
    await mountChat(api, store);

    fireEvent.click(chip());
    await flush();
    await flush();

    expect(api.calls.filter((c) => c.method === "POST" && c.path === "/api/chat/messages")).toHaveLength(1);
    expect(applyCalls(api)).toEqual([
      {
        method: "POST",
        path: "/api/plan/apply",
        body: { path: "COMPARE", expectedRevision: 12, conditionChanges: { etfProductCode: "SP500" } },
      },
    ]);
    expect(store.getState().view?.revision).toBe(13);
  });

  it("질문이 접수되지 않으면(전송 실패) 적용하지 않는다", async () => {
    const { api, store } = mount({ "POST /api/chat/messages": [new Error("network")] });
    await mountChat(api, store);

    fireEvent.click(chip());
    await flush();
    await flush();

    expect(applyCalls(api)).toHaveLength(0);
  });
});
