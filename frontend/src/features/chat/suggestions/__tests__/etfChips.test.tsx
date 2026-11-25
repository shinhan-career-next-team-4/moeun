// @vitest-environment jsdom
// Issue #275: 대화 패널의 다른 ETF 종목 칩. 지금 계획 종목은 빼고 서버 목록(R-1) 순서대로 보이고, 누르면 보통 질문으로 전송된다.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatWindow } from "../../index";
import { COPY } from "../../copy";
import { copy, flush, makeDeps, makeFakeApi, makeReadOnlyStore, page, pendingMsg } from "../../__tests__/helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const PRODUCTS = {
  products: [
    { code: "SP500", displayName: "S&P500 추종 ETF", gainTaxRatePct: 15.4 },
    { code: "069500", displayName: "코스피200 추종 ETF", gainTaxRatePct: 0 },
    { code: "133690", displayName: "나스닥100 추종 ETF", gainTaxRatePct: 15.4 },
  ],
};
const ACCEPTED = { userMessageId: 1030, assistantMessageId: 1031 };

/** 계획 종목이 code인 공유 store. null이면 ETF 0% 계획(종목 코드 없음)이다. */
function storeWithProduct(code: string | null) {
  const store = makeReadOnlyStore({ revision: 1, settledStatus: "CALCULATED" });
  store.set({
    view: {
      revision: 1,
      calcRunId: 1,
      status: "CALCULATED",
      body: { revision: 1, calcRunId: 1, result: { status: "CALCULATED", assumptions: { etfProductCode: code } } },
    },
  });
  return store;
}

/** F-2 폴링은 스크립트대로, ETF 목록은 products(Error면 실패)로 답하는 api. */
async function mount(
  code: string | null,
  replies: Parameters<typeof makeFakeApi>[0],
  products: unknown = PRODUCTS,
) {
  const fake = makeFakeApi(replies);
  const listCalls: string[] = [];
  const api = {
    request: async (method: string, path: string, body?: unknown) => {
      if (method === "GET" && path === "/api/etf-products") {
        listCalls.push(path);
        if (products instanceof Error) throw products;
        return products;
      }
      return fake.request(method, path, body);
    },
  };
  const { deps } = makeDeps(api, storeWithProduct(code));
  render(<ChatWindow deps={deps} />);
  await flush();
  return { fake, listCalls };
}

// 계획에 calcRunId가 있어 자동 설명(POST /api/chat/explanations)도 나간다. 질문 전송만 센다
const questions = (fake: { posts: () => { path: string }[] }) =>
  fake.posts().filter((call) => call.path === "/api/chat/messages");
const chips = () => screen.queryAllByTestId("chat-etf-chip") as HTMLButtonElement[];

describe("칩 목록", () => {
  it("계획 종목이 S&P500이면 코스피200·나스닥100 두 칩만 서버 목록 순서로 보인다", async () => {
    await mount("SP500", { get: [page([], false)] });
    expect(chips().map((c) => c.textContent)).toEqual(["코스피200 추종 ETF", "나스닥100 추종 ETF"]);
    expect(screen.getByRole("group", { name: COPY.etfChipsLabel })).toBeTruthy();
  });

  it("계획 종목이 코스피200이면 S&P500·나스닥100 칩만 보인다", async () => {
    await mount("069500", { get: [page([], false)] });
    expect(chips().map((c) => c.textContent)).toEqual(["S&P500 추종 ETF", "나스닥100 추종 ETF"]);
  });

  it("대화가 이어져도(사용자 메시지가 있어도) 칩은 입력창 위에 남는다", async () => {
    const user = { id: 1, role: "USER", messageType: null, status: "COMPLETED", content: "안녕", citations: [] };
    await mount("SP500", { get: [page([user], false)] });
    expect(chips()).toHaveLength(2);
  });
});

describe("칩을 누르면", () => {
  it("'<종목 이름>로 바꾸면 어때요?'가 일반 질문(F-1)으로 한 번 전송된다", async () => {
    const { fake } = await mount("SP500", { get: [page([], false)], post: [ACCEPTED, ACCEPTED] });
    fireEvent.click(chips()[0]);
    await flush();
    expect(questions(fake)).toHaveLength(1);
    expect(questions(fake)[0]).toMatchObject({
      path: "/api/chat/messages",
      body: { text: "코스피200 추종 ETF로 바꾸면 어때요?" },
    });
  });

  it("답변 대기 중이면 칩이 비활성이고 눌러도 전송하지 않는다", async () => {
    const { fake } = await mount("SP500", { get: [page([pendingMsg(1031)], true, true)] });
    for (const chip of chips()) expect(chip.disabled).toBe(true);
    fireEvent.click(chips()[0]);
    await flush();
    expect(questions(fake)).toHaveLength(0);
  });
});

describe("(예외) 칩을 그리지 않는 경우", () => {
  it("ETF 목록을 받지 못하면 칩 묶음이 없고 대화 입력은 그대로 쓸 수 있다", async () => {
    await mount("SP500", { get: [page([], false)] }, new Error("network"));
    expect(screen.queryByTestId("chat-etf-chips")).toBeNull();
    expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).disabled).toBe(false);
  });

  it("활성 종목이 계획 종목 하나뿐이면 칩 묶음이 없다", async () => {
    await mount("SP500", { get: [page([], false)] }, { products: [PRODUCTS.products[0]] });
    expect(screen.queryByTestId("chat-etf-chips")).toBeNull();
  });

  it("ETF 0% 계획(종목 코드 없음)은 목록을 받지 않고 칩도 없다", async () => {
    const { listCalls } = await mount(null, { get: [page([], false)] });
    expect(listCalls).toHaveLength(0);
    expect(screen.queryByTestId("chat-etf-chips")).toBeNull();
  });
});

describe("문구(CP-05·CP-06)", () => {
  it("칩 문구는 문구표 chat.etfChipsLabel·chat.etfChipQuestion과 같고 '추천'이 없다", () => {
    expect(COPY.etfChipsLabel).toBe(copy["chat.etfChipsLabel"]);
    expect(COPY.etfChipQuestion).toBe(copy["chat.etfChipQuestion"]);
    expect(COPY.etfChipsLabel).not.toContain("추천");
    expect(COPY.etfChipQuestion).not.toContain("추천");
  });
});
