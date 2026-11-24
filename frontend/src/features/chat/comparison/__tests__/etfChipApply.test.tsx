// @vitest-environment jsdom
// #358: 대화창 ETF 버튼은 질문을 보내고, 비교 답이 오면 같은 종목을 내 계획에 적용한다(결과 화면 ETF 기준도 바뀐다).
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { doneMsg, flush, page, tick, userMsg } from "../../__tests__/helpers";
import { comparison, makeApi, makeStore, mountChat, planView } from "./helpers";

const PRODUCTS = {
  products: [
    { code: "SP500", displayName: "S&P500 추종 ETF", gainTaxRatePct: 15.4 },
    { code: "133690", displayName: "나스닥100 추종 ETF", gainTaxRatePct: 15.4 },
  ],
};
const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "S&P500으로 바꾸면 평균 자산이 줄어요");

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

function mount(replies: Record<string, unknown[]>) {
  const api = makeApi({
    "GET /api/etf-products": [PRODUCTS],
    "POST /api/chat/messages": [{ userMessageId: 1030, assistantMessageId: 1031 }],
    ...replies,
  });
  const env = makeStore({ initialRevision: 12, planReplies: [etfPlan(13)] });
  env.store.acceptPlanView(etfPlan(12));
  return { api, ...env };
}

const chip = () => screen.getAllByTestId("chat-etf-chip")[0] as HTMLButtonElement;

describe("ETF 버튼 = 질문 + 적용", () => {
  it("버튼을 누르면 질문이 가고, 비교 답이 오면 F-5 적용이 한 번 나가 계획 revision이 바뀐다", async () => {
    const { api, store } = mount({
      F2: [page([], false), page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
    });
    await mountChat(api, store);

    fireEvent.click(chip());
    await flush();
    await tick(5000);
    await flush();
    await flush();

    const questions = api.calls.filter((c) => c.method === "POST" && c.path === "/api/chat/messages");
    expect(questions).toHaveLength(1);
    expect(api.f5Calls()).toEqual([{ method: "POST", path: "/api/chat/messages/1031/apply", body: { expectedRevision: 12 } }]);
    expect(store.getState().view?.revision).toBe(13);
  });

  it("비교가 아닌 답(되묻기 등)이 오면 적용하지 않는다", async () => {
    const clarify = doneMsg(1031, "CLARIFY", "어떤 종목인지 알려 주세요");
    const { api, store } = mount({
      F2: [page([], false), page([userMsg(1030), clarify], false)],
    });
    await mountChat(api, store);

    fireEvent.click(chip());
    await flush();
    await tick(5000);
    await flush();

    expect(api.f5Calls()).toHaveLength(0);
  });
});
