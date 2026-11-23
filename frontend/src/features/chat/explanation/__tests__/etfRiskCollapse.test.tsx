// @vitest-environment jsdom
// Issue #283: 결과 설명의 ETF 위험 설명(근거·출처)은 ETF 비중이 있을 때만, 기본으로 접어서 보여 준다.
// ETF 비중은 계획 결과의 assumptions.savingsRatioPct(적용된 적금 비중)로 본다. 100이면 ETF 0%다.
import { act, cleanup, fireEvent, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy } from "../../__tests__/helpers";
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
const TITLE = copy["chat.etfRiskTitle"];

/** 적용된 적금 비중이 savingsRatioPct인 계획을 가진 store. */
function storeWithRatio(savingsRatioPct: number) {
  const made = makeStore();
  const body = view(1, "CALCULATED", freshId());
  (body.result as { assumptions: Record<string, unknown> }).assumptions.savingsRatioPct = savingsRatioPct;
  made.store.acceptPlanView(body);
  return made.store;
}

async function mountWith(store: unknown, messages: Parameters<typeof chatPage>[0]) {
  return mountChat(store, { get: [chatPage(messages, false)] });
}

const toggleOf = (el: HTMLElement) => within(el).queryByRole("button", { name: new RegExp(TITLE) });

describe("#283 ETF 위험 설명 접기", () => {
  it("ETF 비중 0%(적금 100%) 계획이면 결과 설명에 ETF 위험 설명 박스를 그리지 않는다", async () => {
    await mountWith(storeWithRatio(100), [explanationDone(4001, CONTENT, CITATIONS)]);
    const message = bubble(4001)!;
    expect(message).toBeTruthy();
    expect(toggleOf(message)).toBeNull();
    expect(within(message).queryByTestId("chat-citations")).toBeNull();
    expect(within(message).queryAllByRole("link")).toHaveLength(0);
  });

  it("ETF 비중이 있으면 박스는 접힌 채로 나온다: 제목 버튼만 보이고 aria-expanded=false, 출처 링크는 없다", async () => {
    await mountWith(storeWithRatio(50), [explanationDone(4002, CONTENT, CITATIONS)]);
    const message = bubble(4002)!;
    const toggle = toggleOf(message)!;
    expect(toggle).toBeTruthy();
    expect(toggle.textContent).toContain("▸");
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(within(message).queryByTestId("chat-citations")).toBeNull();
    expect(within(message).queryAllByRole("link")).toHaveLength(0);
  });

  it("제목을 누르면 본문과 출처가 펼쳐지고, 다시 누르면 접힌다(aria-expanded·aria-controls)", async () => {
    await mountWith(storeWithRatio(50), [explanationDone(4003, CONTENT, CITATIONS)]);
    const message = bubble(4003)!;
    const toggle = toggleOf(message)!;

    act(() => fireEvent.click(toggle));
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(toggle.textContent).toContain("▾");
    const list = within(message).getByTestId("chat-citations");
    expect(toggle.getAttribute("aria-controls")).toBe(list.closest("[id]")!.id);
    expect(within(list).getAllByRole("link").map((a) => a.textContent)).toEqual(CITATIONS.map((c) => c.title));

    act(() => fireEvent.click(toggle));
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(within(message).queryByTestId("chat-citations")).toBeNull();
  });

  it("제목은 키보드로 여닫을 수 있는 진짜 버튼이다(type=button, 포커스 가능)", async () => {
    await mountWith(storeWithRatio(50), [explanationDone(4004, CONTENT, CITATIONS)]);
    const toggle = toggleOf(bubble(4004)!)! as HTMLButtonElement;
    expect(toggle.tagName).toBe("BUTTON");
    expect(toggle.type).toBe("button");
    toggle.focus();
    expect(document.activeElement).toBe(toggle);
  });

  it("근거가 비어 있으면 ETF 비중이 있어도 빈 박스를 그리지 않는다", async () => {
    await mountWith(storeWithRatio(50), [explanationDone(4005, CONTENT, [])]);
    const message = bubble(4005)!;
    expect(toggleOf(message)).toBeNull();
    expect(within(message).queryByTestId("chat-citations")).toBeNull();
  });

  it("질문 답·비교 답(ANSWER·RECALC_RESULT)의 출처는 지금처럼 접지 않고 바로 보인다", async () => {
    await mountWith(storeWithRatio(100), [
      explanationDone(4011, "질문 답", CITATIONS, "ANSWER"),
      explanationDone(4012, "비교 답", CITATIONS, "RECALC_RESULT"),
    ]);
    for (const id of [4011, 4012]) {
      const message = bubble(id)!;
      expect(toggleOf(message), String(id)).toBeNull();
      expect(within(message).getAllByRole("link"), String(id)).toHaveLength(CITATIONS.length);
    }
  });

  it("펼친 뒤에도 말풍선 본문은 서버 content 그대로다(제목·출처 영역 밖)", async () => {
    await mountWith(storeWithRatio(50), [explanationDone(4006, CONTENT, CITATIONS)]);
    const message = bubble(4006)!;
    act(() => fireEvent.click(toggleOf(message)!));
    const clone = message.cloneNode(true) as HTMLElement;
    clone.querySelectorAll('[data-testid="chat-etf-risk"]').forEach((node) => node.remove());
    expect(clone.textContent).toBe(CONTENT);
  });
});
