// @vitest-environment jsdom
// Issue #55: 목표 금액을 바꾼 대화 비교(D-090)도 카드로 그린다. 실서버 연결에서 "비교 결과를 불러오지 못했어요"로 떴다.
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, page, userMsg } from "../../__tests__/helpers";
import { buttonOf, card, comparison, conditionRows, failedNotice, makeApi, makeStore, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const ID = 1034;

async function mountGoalChanged() {
  const body = comparison("get-comparison.200-goal-changed.json");
  const api = makeApi({
    F2: [page([userMsg(ID - 1), doneMsg(ID, "RECALC_RESULT", "목표 금액을 바꿔 계산했어요")], false)],
    [`F4 ${ID}`]: [body],
  });
  await mountChat(api, makeStore().store);
  return body;
}

describe("목표 금액을 바꾼 비교(D-090)", () => {
  it("카드가 뜨고 목표 금액 행이 맨 앞에 강조되어 display 그대로 보인다", async () => {
    const body = await mountGoalChanged();
    expect(failedNotice(ID)).toBeNull();
    expect(card(ID)).not.toBeNull();
    const rows = conditionRows(ID);
    expect(rows.map((row) => row.getAttribute("data-key"))).toEqual(["goalAmount"]); // 바뀐 조건만(#417)
    const goal = rows[0];
    expect(goal.getAttribute("data-changed")).toBe("true");
    expect(goal.textContent).toContain(copy["condition.goalAmount"]);
    expect(goal.textContent).toContain(body.display["conditions.current.goalAmount"]);
    expect(goal.textContent).toContain(body.display["conditions.changed.goalAmount"]);
  });

  it("달성 여부가 각자 목표 기준이라는 안내를 display 값으로 채워 보인다", async () => {
    const body = await mountGoalChanged();
    const note = card(ID)?.querySelector('[data-testid="chat-comparison-goal-note"]');
    const expected = copy["chat.goalAmountChangedNote"]
      .replace("{currentGoal}", body.display["conditions.current.goalAmount"])
      .replace("{changedGoal}", body.display["conditions.changed.goalAmount"]);
    expect(note?.textContent).toBe(expected);
  });

  it("적용 버튼이 활성이다", async () => {
    await mountGoalChanged();
    expect(buttonOf(ID).disabled).toBe(false);
  });

  it("목표 금액을 바꾸지 않은 비교에는 목표 금액 행과 안내가 없다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), doneMsg(1031, "RECALC_RESULT", "월 저축액을 바꿔 계산했어요")], false)],
      "F4 1031": [comparison("get-comparison.200.json")],
    });
    await mountChat(api, makeStore().store);
    expect(conditionRows(1031).map((row) => row.getAttribute("data-key"))).toEqual(["monthlySavingAmount"]);
    expect(card(1031)?.querySelector('[data-testid="chat-comparison-goal-note"]')).toBeNull();
  });

  it("목표 금액이 바뀌었는데 목표 금액 display가 없으면 카드 대신 불러오기 실패다", async () => {
    const body = comparison("get-comparison.200-goal-changed.json");
    delete body.display["conditions.changed.goalAmount"];
    const api = makeApi({
      F2: [page([userMsg(ID - 1), doneMsg(ID, "RECALC_RESULT", "목표 금액을 바꿔 계산했어요")], false)],
      [`F4 ${ID}`]: [body],
    });
    await mountChat(api, makeStore().store);
    expect(failedNotice(ID)).not.toBeNull();
  });
});

describe("목표 금액을 바꿔 한쪽이 이미 목표를 넘은 비교(D-092)", () => {
  async function mountExceeded(file: string, id: number) {
    const body = comparison(file);
    const api = makeApi({
      F2: [page([userMsg(id - 1), doneMsg(id, "RECALC_RESULT", "목표 금액을 바꿔 계산했어요")], false)],
      [`F4 ${id}`]: [body],
    });
    await mountChat(api, makeStore().store);
    return body;
  }

  const exceededText = (body: { display: Record<string, string> }, side: "current" | "changed") =>
    copy["chat.cardAlreadyExceeded"]
      .replace("{heldAssets}", body.display[`conditions.${side}.heldAssets`])
      .replace("{goalAmount}", body.display[`conditions.${side}.goalAmount`]);

  it("바꾼 쪽이 초과면 카드가 뜨고 초과 안내와 적용 뒤 상태 안내를 보이며, 현재 쪽은 기준 시나리오 값을 보인다", async () => {
    const body = await mountExceeded("get-comparison.200-changed-exceeded.json", 1036);
    expect(failedNotice(1036)).toBeNull();
    const el = card(1036)!;
    expect(el.querySelector('[data-testid="chat-comparison-changed-exceeded"]')?.textContent).toBe(exceededText(body, "changed"));
    expect(el.querySelector('[data-testid="chat-comparison-changed-exceeded-note"]')?.textContent).toBe(copy["chat.changedAlreadyExceeded"]);
    const current = el.querySelector('[data-testid="chat-comparison-current-calculated"]')!;
    expect(current.textContent).toContain(copy["compare.colCurrent"]);
    expect(current.textContent).toContain(body.display["current.AVG.afterTaxAsset"]);
    expect(current.textContent).toContain(body.display["current.AVG.gapToGoal"]);
    expect(el.querySelectorAll('[data-testid="chat-comparison-scenario"]')).toHaveLength(0);
    expect(buttonOf(1036).disabled).toBe(false);
  });

  it("현재 쪽이 초과면 현재 쪽에 초과 안내, 바꾼 쪽에 기준 시나리오 값을 보이고 적용 뒤 상태 안내는 없다", async () => {
    const body = await mountExceeded("get-comparison.200-asymmetric-exceeded.json", 1035);
    const el = card(1035)!;
    expect(el.querySelector('[data-testid="chat-comparison-current-exceeded"]')?.textContent).toBe(exceededText(body, "current"));
    const changed = el.querySelector('[data-testid="chat-comparison-changed-calculated"]')!;
    expect(changed.textContent).toContain(copy["compare.colChanged"]);
    expect(changed.textContent).toContain(body.display["changed.AVG.afterTaxAsset"]);
    expect(changed.textContent).toContain(body.display["changed.AVG.gapToGoal"]);
    expect(el.querySelector('[data-testid="chat-comparison-changed-exceeded-note"]')).toBeNull();
  });

  it("초과한 쪽의 보유 자산 display가 없으면 불러오기 실패다", async () => {
    const body = comparison("get-comparison.200-changed-exceeded.json");
    delete body.display["conditions.changed.heldAssets"];
    const api = makeApi({
      F2: [page([userMsg(1035), doneMsg(1036, "RECALC_RESULT", "목표 금액을 바꿔 계산했어요")], false)],
      "F4 1036": [body],
    });
    await mountChat(api, makeStore().store);
    expect(failedNotice(1036)).not.toBeNull();
  });
});
