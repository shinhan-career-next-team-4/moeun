// @vitest-environment jsdom
// Issue #345: 대화로 목표 이름을 바꾼 비교. changedKeys에 goalCustomName이 오고 최상위 goalCustomName이 바꿀 이름이다.
// 지금 이름은 공유 계획 store의 계획(goal.<goalType> 문구 또는 plan.goalCustomName)에서 읽는다(조건 바와 같은 규칙).
import { cleanup, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, flush, page, userMsg } from "../../__tests__/helpers";
import {
  buttonOf,
  card,
  cardOf,
  cellText,
  comparison,
  conditionRows,
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

const ID = 1037;
const NEW_NAME = "가게 보증금";

async function mount(body: Record<string, unknown>, store = makeStore().store) {
  const api = makeApi({
    F2: [page([userMsg(ID - 1), doneMsg(ID, "RECALC_RESULT", "목표 이름을 바꿔 계산했어요")], false)],
    [`F4 ${ID}`]: [body],
    [`F5 ${ID}`]: [{ revision: 13 }],
  });
  await mountChat(api, store);
  return api;
}

function nameOnly() {
  return comparison("get-comparison.200-goal-name.json");
}

/** 목표 금액 변경 목에 이름 변경을 더한 비교. */
function nameAndAmount() {
  const body = comparison("get-comparison.200-goal-changed.json", {
    messageId: ID,
    changedKeys: ["goalAmount", "goalCustomName"],
  });
  return { ...body, goalCustomName: NEW_NAME };
}

const title = () => cardOf(ID).querySelector('[data-testid="chat-comparison-title"]')?.textContent;
const nameOnlyNote = () => card(ID)?.querySelector('[data-testid="chat-comparison-goal-name-note"]') ?? null;
const goalAmountNote = () => card(ID)?.querySelector('[data-testid="chat-comparison-goal-note"]') ?? null;

describe("목표 이름만 바꾼 비교(#345)", () => {
  it("목표 이름 행이 맨 앞에 강조되고 지금 이름 → 바꿀 이름을 보인다", async () => {
    const body = nameOnly();
    expect(body.goalCustomName).toBe(NEW_NAME);
    await mount(body);
    expect(failedNotice(ID)).toBeNull();
    const rows = conditionRows(ID);
    expect(rows.map((row) => row.getAttribute("data-key"))).toEqual(["goalCustomName"]); // 바뀐 조건만(#417)
    const nameRow = rows[0];
    expect(nameRow.getAttribute("data-changed")).toBe("true");
    expect(cellText(nameRow, "chat-comparison-label")).toBe(copy["condition.goalCustomName"]);
    // 기본 계획 목은 HOUSE라 지금 이름은 문구표의 goal.HOUSE다
    expect(cellText(nameRow, "chat-comparison-current")).toBe(copy["goal.HOUSE"]);
    expect(cellText(nameRow, "chat-comparison-changed")).toBe(NEW_NAME);
    // 숫자 조건은 그대로라 강조하지 않는다
    for (const row of rows.slice(1)) expect(row.getAttribute("data-changed")).toBe("false");
  });

  it("제목은 목표 이름만, 결과가 그대로라는 안내를 보이고 목표 금액 안내는 없다", async () => {
    await mount(nameOnly());
    expect(title()).toBe(copy["comparison.title"].replace("{conditions}", copy["condition.goalCustomName"]));
    expect(nameOnlyNote()?.textContent).toBe(copy["chat.goalNameOnlyNote"]);
    expect(goalAmountNote()).toBeNull();
  });

  it("시나리오 행은 '변화 없음'과 같음 방향으로 보이고 적용 버튼은 활성이라 F-5를 부른다", async () => {
    const api = await mount(nameOnly());
    const rows = scenarioRows(ID);
    expect(rows.length).toBe(3);
    for (const row of rows) {
      expect(cellText(row, "chat-comparison-delta")).toBe("변화 없음");
      expect(row.querySelector('[data-testid="chat-comparison-delta-tone"]')?.getAttribute("data-direction")).toBe("same");
    }
    const button = buttonOf(ID);
    expect(button.disabled).toBe(false);
    fireEvent.click(button);
    await flush();
    expect(api.f5Calls().map((call) => call.path)).toEqual([`/api/chat/messages/${ID}/apply`]);
  });

  it("계획이 이미 직접 입력(CUSTOM)이면 지금 이름은 plan.goalCustomName이다", async () => {
    const view = planView(12);
    view.plan.goalType = "CUSTOM";
    view.plan.goalCustomName = "여행 자금";
    const { store } = makeStore({ initialRevision: null });
    store.acceptPlanView(view);
    await mount(nameOnly(), store);
    const nameRow = conditionRows(ID)[0];
    expect(cellText(nameRow, "chat-comparison-current")).toBe("여행 자금");
  });

  it("긴 이름도 줄바꿈되어 카드 밖으로 넘치지 않는다", async () => {
    const long = "가".repeat(100);
    await mount({ ...nameOnly(), goalCustomName: long });
    const nameRow = conditionRows(ID)[0];
    const changed = nameRow.querySelector<HTMLElement>('[data-testid="chat-comparison-changed"]');
    expect(changed?.textContent).toBe(long);
    expect(changed?.className).toContain("wrap-anywhere");
    expect(nameRow.querySelector<HTMLElement>('[data-testid="chat-comparison-current"]')?.className).toContain("wrap-anywhere");
  });

  it.each([
    ["없음", undefined],
    ["빈 문자열", "  "],
    ["문자열 아님", 3],
  ])("goalCustomName이 바뀌었는데 이름이 %s이면 카드 대신 불러오기 실패다", async (_label, value) => {
    const body: Record<string, unknown> = nameOnly();
    if (value === undefined) delete body.goalCustomName;
    else body.goalCustomName = value;
    await mount(body);
    expect(failedNotice(ID)).not.toBeNull();
    expect(card(ID)).toBeNull();
  });
});

describe("목표 이름과 목표 금액을 함께 바꾼 비교(#345)", () => {
  it("목표 이름 행, 목표 금액 행 순서로 둘 다 강조하고 제목에 둘 다 들어간다", async () => {
    const body = nameAndAmount();
    await mount(body);
    const rows = conditionRows(ID);
    expect(rows.map((row) => row.getAttribute("data-key"))).toEqual(["goalCustomName", "goalAmount"]);
    expect(rows[0].getAttribute("data-changed")).toBe("true");
    expect(rows[1].getAttribute("data-changed")).toBe("true");
    expect(cellText(rows[0], "chat-comparison-changed")).toBe(NEW_NAME);
    expect(cellText(rows[1], "chat-comparison-changed")).toBe(body.display["conditions.changed.goalAmount"]);
    expect(title()).toBe(
      copy["comparison.title"].replace("{conditions}", `${copy["condition.goalCustomName"]}·${copy["condition.goalAmount"]}`),
    );
  });

  it("목표 금액 안내는 보이고 '결과가 그대로' 안내는 없다", async () => {
    await mount(nameAndAmount());
    expect(goalAmountNote()).not.toBeNull();
    expect(nameOnlyNote()).toBeNull();
  });
});

describe("이름을 바꾸지 않은 비교", () => {
  it("목표 이름 행과 이름 안내가 없다", async () => {
    await mount(comparison("get-comparison.200.json", { messageId: ID }));
    expect(conditionRows(ID).map((row) => row.getAttribute("data-key"))).toEqual(["monthlySavingAmount"]);
    expect(nameOnlyNote()).toBeNull();
  });
});
