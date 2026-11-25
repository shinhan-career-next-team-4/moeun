// @vitest-environment jsdom
// Issue #418: 대화 중 바꾼 조건을 다음 질문에도 유지한다(D-103). 카드 상단에 쌓인 조건을 보이고, 대화창을 닫으면 변경 목록을 비운다.
// 비교 기준은 F-4 conditions.current(직전 누적 계획)라 #417 카드의 기본값으로 이어진다.
import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, flush, page, userMsg } from "../../__tests__/helpers";
import { ChatWindow } from "../../index";
import { card, comparison, conditionRows, makeApi, makeStore, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const ID = 1031;
const RESET = "DELETE /api/chat/condition-changes";

/** 비중 30:70 → 월 150만원 → 월 200만원으로 세 번째 변경한 비교. current는 두 번째 변경까지 누적된 계획이다. */
function thirdChange() {
  const b = comparison("get-comparison.200.json", { changedKeys: ["monthlySavingAmount"] });
  b.conditions.current = { ...b.conditions.current, savingsRatioPct: 30, monthlySavingAmount: 1_500_000 };
  b.conditions.changed = { ...b.conditions.current, monthlySavingAmount: 2_000_000 };
  b.display["conditions.current.savingsRatioPct"] = "적금 30 : ETF 70";
  b.display["conditions.changed.savingsRatioPct"] = "적금 30 : ETF 70";
  b.display["conditions.current.monthlySavingAmount"] = "약 150만원";
  b.display["conditions.changed.monthlySavingAmount"] = "약 200만원";
  return { ...b, accumulatedKeys: ["savingsRatioPct", "monthlySavingAmount"] };
}

async function mountWith(body: unknown, extra: Record<string, unknown[]> = {}) {
  const api = makeApi({
    F2: [page([userMsg(ID - 1), doneMsg(ID, "RECALC_RESULT", "계산했어요")], false)],
    [`F4 ${ID}`]: [body],
    ...extra,
  });
  const mounted = await mountChat(api, makeStore().store);
  return { api, ...mounted };
}

const accumulatedText = () => card(ID)?.querySelector('[data-testid="chat-comparison-accumulated"]')?.textContent ?? null;

describe("결과 카드 상단의 쌓인 조건(#418)", () => {
  it("쌓인 조건을 카드 순서로 '라벨 값'을 이어 '… 적용 중'으로 보인다", async () => {
    await mountWith(thirdChange());
    expect(accumulatedText()).toBe(
      copy["comparison.accumulated"].replace("{conditions}", "월 저축액 약 200만원, 저축 비중 적금 30 : ETF 70"),
    );
  });

  it("쌓인 조건이 없으면(accumulatedKeys 없음) 표시하지 않는다", async () => {
    await mountWith(comparison("get-comparison.200.json"));
    expect(card(ID)).not.toBeNull();
    expect(accumulatedText()).toBeNull();
  });

  it("허용 밖 키는 무시한다", async () => {
    await mountWith({ ...comparison("get-comparison.200.json"), accumulatedKeys: ["heldAssets", "monthlySavingAmount"] });
    expect(accumulatedText()).toBe(copy["comparison.accumulated"].replace("{conditions}", "월 저축액 약 50만원"));
  });

  it("비교 기준은 두 번째 변경까지 누적된 계획이라 이번에 바꾼 월 저축액만 행으로 보인다", async () => {
    await mountWith(thirdChange());
    const rows = conditionRows(ID);
    expect(rows.map((row) => row.getAttribute("data-key"))).toEqual(["monthlySavingAmount"]);
    expect(rows[0].textContent).toContain("약 150만원");
    expect(rows[0].textContent).toContain("약 200만원");
  });
});

describe("대화창을 닫으면 변경 목록을 비운다(#418)", () => {
  it("열려 있다가 닫히면 DELETE /api/chat/condition-changes를 한 번 부른다", async () => {
    const { api, deps, view } = await mountWith(thirdChange(), { [RESET]: [{ accumulatedKeys: [] }] });
    expect(api.calls.filter((c) => `${c.method} ${c.path}` === RESET)).toHaveLength(0);

    view.rerender(<ChatWindow deps={deps} visible={false} />);
    await flush();
    view.rerender(<ChatWindow deps={deps} visible={false} />);
    await flush();

    expect(api.calls.filter((c) => `${c.method} ${c.path}` === RESET)).toHaveLength(1);
  });

  it("처음부터 닫혀 있던 대화창은 부르지 않는다", async () => {
    const { api, deps, view } = await mountWith(thirdChange(), { [RESET]: [{ accumulatedKeys: [] }] });
    view.unmount();
    // 닫힌 채로 처음 그린 대화창
    const hidden = render(<ChatWindow deps={deps} visible={false} />);
    await flush();
    expect(api.calls.filter((c) => `${c.method} ${c.path}` === RESET)).toHaveLength(0);
    hidden.unmount();
  });

  it("비우기가 실패해도 대화창은 그대로다(오류를 밖으로 내지 않는다)", async () => {
    const { deps, view } = await mountWith(thirdChange(), { [RESET]: [new Error("network")] });
    view.rerender(<ChatWindow deps={deps} visible={false} />);
    await flush();
    view.rerender(<ChatWindow deps={deps} visible />);
    await flush();
    expect(card(ID)).not.toBeNull();
  });
});
