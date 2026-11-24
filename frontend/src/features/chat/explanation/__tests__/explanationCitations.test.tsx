// @vitest-environment jsdom
// Issue #374: ETF 위험 근거는 결과 화면 고정 블록(EtfRiskNotice)이 맡는다. 대화 패널은 결과 설명의 출처를
// 'ETF 위험 설명'으로 따로 접지 않고, 질문 답·비교 답과 같은 출처 목록으로 보인다(#283의 접기 블록은 지웠다).
import { cleanup, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { bubble, chatPage, explanationDone, freshId, makeStore, mountChat, view } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const CITATIONS = [
  { title: "KODEX 미국S&P500 투자설명서", sourceUrl: "https://example.com/a.pdf", orgName: "삼성자산운용" },
  { title: "TIGER 미국S&P500 투자설명서", sourceUrl: "https://example.com/b.pdf", orgName: "미래에셋자산운용" },
];
const CONTENT = "지금 계획대로 이어 가면 평균일 때 3년 뒤 약 1,712만원이 돼요.";

function etfPlanStore() {
  const made = makeStore();
  made.store.acceptPlanView(view(1, "CALCULATED", freshId()));
  return made.store;
}

async function mountWith(messages: Parameters<typeof chatPage>[0]) {
  return mountChat(etfPlanStore(), { get: [chatPage(messages, false)] });
}

describe("#374 결과 설명의 출처", () => {
  it("결과 설명에 출처가 오면 접지 않고 다른 답처럼 바로 보인다", async () => {
    await mountWith([explanationDone(5001, CONTENT, CITATIONS)]);
    const message = bubble(5001)!;

    expect(within(message).getAllByRole("link").map((a) => a.textContent)).toEqual(CITATIONS.map((c) => c.title));
    expect(within(message).queryByRole("button", { name: /ETF 위험 설명/ })).toBeNull();
  });

  it("(예외) 결과 설명에 출처가 없으면 출처 목록도 'ETF 위험 설명' 버튼도 없다", async () => {
    await mountWith([explanationDone(5002, CONTENT, [])]);
    const message = bubble(5002)!;

    expect(within(message).queryByTestId("chat-citations")).toBeNull();
    expect(within(message).queryByRole("button", { name: /ETF 위험 설명/ })).toBeNull();
    expect(message.textContent).toBe(CONTENT);
  });
});
