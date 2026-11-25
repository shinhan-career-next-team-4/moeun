// @vitest-environment jsdom
// Issue #417: 대화 결과 카드는 비교 기준과 다른 조건만 행으로 보이고, 시나리오마다 증가 줄을 한 줄로 합친다.
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg } from "../../__tests__/helpers";
import type { ChatMessage } from "../../types";
import { comparison } from "./helpers";

afterEach(cleanup);

const loadTypes = () => import("../types");
const loadSummary = () => import("../summary");
const loadResult = () => import("../ComparisonResult");

const ID = 1031;
type Body = ReturnType<typeof comparison>;

/** 저축 비중도 50 → 70으로 바꾼 본문(월 저축액과 함께 2개 변경). */
function twoChanged(): Body {
  const b = comparison();
  b.changedKeys = ["monthlySavingAmount", "savingsRatioPct"];
  b.conditions.changed.savingsRatioPct = 70;
  b.display["conditions.changed.savingsRatioPct"] = "적금 70 : ETF 30";
  return b;
}

/** 월 저축액을 줄여 자산이 줄고 계속 미달인 본문. */
function decreased(): Body {
  const b = comparison();
  b.conditions.changed.monthlySavingAmount = 200000;
  b.display["conditions.changed.monthlySavingAmount"] = "약 20만원";
  for (const item of b.diff.byScenario) {
    item.afterTaxAssetDelta = -470000;
    item.achievementChange = "STAYS_MISSED";
    b.display[`diff.${item.scenario}.afterTaxAssetDelta`] = "약 47만원 감소";
    b.display[`changed.${item.scenario}.gapToGoal`] = "-약 311만원 미달";
  }
  return b;
}

async function renderCard(body: Body, baselineConditions?: Record<string, unknown>) {
  const { parseComparison } = await loadTypes();
  const { ComparisonResult } = await loadResult();
  const data = parseComparison(body);
  expect(data, "본문이 카드 데이터로 읽혀야 한다").not.toBeNull();
  const comparisonState = {
    snapshot: {
      revision: 1,
      entries: new Map([[ID, { kind: "ready" as const, data: data! }]]),
      stale: new Set<number>(),
      off: new Set<number>(),
      busy: new Set<number>(),
      toast: null,
      undoRevision: null,
      undoToastId: 0,
      feedback: null,
    },
    controller: { apply: vi.fn() } as never,
    latestId: ID,
    hideApply: true,
  };
  const message = doneMsg(ID, "RECALC_RESULT", "계산했어요") as unknown as ChatMessage;
  render(<ComparisonResult comparison={comparisonState} message={message} baselineConditions={baselineConditions} />);
}

const rows = () => Array.from(document.querySelectorAll<HTMLElement>('[data-testid="chat-comparison-condition"]'));
const rowKeys = () => rows().map((row) => row.getAttribute("data-key"));
const scenarioRows = () => Array.from(document.querySelectorAll<HTMLElement>('[data-testid="chat-comparison-scenario"]'));
const byTestId = (root: ParentNode, id: string) => Array.from(root.querySelectorAll<HTMLElement>(`[data-testid="${id}"]`));
/** 화면에 보이는 글자. 화면 낭독기 전용(sr-only) 문구는 뺀다. */
function visibleText(el: HTMLElement): string {
  const copyEl = el.cloneNode(true) as HTMLElement;
  copyEl.querySelectorAll(".sr-only").forEach((node) => node.remove());
  return copyEl.textContent ?? "";
}
const text = (id: string) => document.querySelector(`[data-testid="${id}"]`)?.textContent ?? null;

describe("바뀐 조건 고르기(changedConditionKeys)", () => {
  it("비교 기준과 값이 다른 조건만 카드 행 순서로 고른다", async () => {
    const { changedConditionKeys } = await loadSummary();
    const changed = { monthlySavingAmount: 500000, savingsRatioPct: 70, savingsRateInputPct: 3.12, targetPeriodMonths: 36 };
    const baseline = { monthlySavingAmount: 300000, savingsRatioPct: 50, savingsRateInputPct: 3.12, targetPeriodMonths: 36 };
    expect(changedConditionKeys(baseline, changed, new Set())).toEqual(["monthlySavingAmount", "savingsRatioPct"]);
  });

  it("같은 값이면 하나도 고르지 않는다", async () => {
    const { changedConditionKeys } = await loadSummary();
    const same = { monthlySavingAmount: 300000, savingsRatioPct: 50, savingsRateInputPct: 3.12, targetPeriodMonths: 36 };
    expect(changedConditionKeys(same, { ...same }, new Set(["monthlySavingAmount"]))).toEqual([]);
  });

  it("ETF 종목 코드가 다르면 맨 뒤에 고른다", async () => {
    const { changedConditionKeys } = await loadSummary();
    const base = { monthlySavingAmount: 300000, savingsRatioPct: 50, savingsRateInputPct: 3.12, targetPeriodMonths: 36 };
    expect(changedConditionKeys({ ...base, etfProductCode: "SP500" }, { ...base, targetPeriodMonths: 48, etfProductCode: "069500" }, new Set())).toEqual([
      "targetPeriodMonths",
      "etfProductCode",
    ]);
  });

  it("한쪽에 값이 없는 조건은 서버 changedKeys를 따른다", async () => {
    const { changedConditionKeys } = await loadSummary();
    const base = { monthlySavingAmount: 300000, savingsRatioPct: 50, savingsRateInputPct: 3.12, targetPeriodMonths: 36 };
    expect(changedConditionKeys(base, base, new Set(["etfProductCode"]))).toEqual(["etfProductCode"]);
    expect(changedConditionKeys(base, base, new Set())).toEqual([]);
  });
});

describe("증가 줄 문구(resultLine)", () => {
  it("달성이고 자산이 늘었으면 '▲ 약 N만원 증가 · 목표보다 +M만원'", async () => {
    const { resultLine } = await loadSummary();
    const line = resultLine({ direction: "up", assetDelta: "약 789만원 증가", gapToGoal: "+약 501만원 달성", achieved: true });
    expect(`${line.mark} ${line.text}`).toBe("▲ 약 789만원 증가 · 목표보다 +501만원");
  });

  it("미달이고 자산이 줄었으면 '▼ 약 N만원 감소 · 목표까지 -M만원'", async () => {
    const { resultLine } = await loadSummary();
    const line = resultLine({ direction: "down", assetDelta: "약 47만원 감소", gapToGoal: "-약 311만원 미달", achieved: false });
    expect(`${line.mark} ${line.text}`).toBe("▼ 약 47만원 감소 · 목표까지 -311만원");
  });

  it("목표 대비가 1만원 미만·0원이어도 서버 문자열의 금액을 그대로 쓴다", async () => {
    const { resultLine } = await loadSummary();
    expect(resultLine({ direction: "same", assetDelta: "변화 없음", gapToGoal: "0원 달성", achieved: true }).text).toBe("변화 없음 · 목표보다 0원");
    expect(resultLine({ direction: "up", assetDelta: "약 3만원 증가", gapToGoal: "+1만원 미만 달성", achieved: true }).text).toBe(
      "약 3만원 증가 · 목표보다 +1만원 미만",
    );
  });
});

describe("달성 상태 문구(achievementNote)", () => {
  it("상태가 바뀔 때만 문구가 있다", async () => {
    const { achievementNote } = await loadSummary();
    expect(achievementNote("MISS_TO_ACHIEVE")).toBe(copy["achievementChange.MISS_TO_ACHIEVE"]);
    expect(achievementNote("ACHIEVE_TO_MISS")).toBe(copy["achievementChange.ACHIEVE_TO_MISS"]);
    expect(achievementNote("STAYS_ACHIEVED")).toBeNull();
    expect(achievementNote("STAYS_MISSED")).toBeNull();
  });
});

describe("대화 결과 카드(#417)", () => {
  it("월 저축액만 바꾸면 조건 행은 하나이고 '나머지 조건은 그대로예요'가 있다", async () => {
    await renderCard(comparison());
    expect(rowKeys()).toEqual(["monthlySavingAmount"]);
    expect(text("chat-comparison-unchanged")).toBe(copy["comparison.othersUnchanged"]);
  });

  it("조건 2개를 바꾸면 그 2개만 행으로 보인다", async () => {
    await renderCard(twoChanged());
    expect(rowKeys()).toEqual(["monthlySavingAmount", "savingsRatioPct"]);
    expect(rows()[1].textContent).toContain("적금 70 : ETF 30");
  });

  it("결과 행은 좋았던 때·평균·나빴던 때 세 개 모두 보인다", async () => {
    await renderCard(comparison());
    expect(scenarioRows().map((row) => row.getAttribute("data-scenario"))).toEqual(["GOOD", "AVG", "BAD"]);
  });

  it("달성·증가 시나리오의 증가 줄은 한 줄이고 중복되지 않는다", async () => {
    const b = comparison();
    await renderCard(b);
    const avg = scenarioRows()[1];
    const lines = byTestId(avg, "chat-comparison-result-line");
    expect(lines).toHaveLength(1);
    expect(visibleText(lines[0])).toBe("▲ 약 789만원 증가 · 목표보다 +501만원");
    expect(avg.textContent?.split(b.display["diff.AVG.afterTaxAssetDelta"]).length).toBe(2);
    expect(byTestId(avg, "chat-comparison-gap-delta")).toHaveLength(0);
  });

  it("미달·감소 시나리오는 '▼ 약 N만원 감소 · 목표까지 -M만원' 한 줄이다", async () => {
    await renderCard(decreased());
    const line = byTestId(scenarioRows()[0], "chat-comparison-result-line");
    expect(line).toHaveLength(1);
    expect(visibleText(line[0])).toBe("▼ 약 47만원 감소 · 목표까지 -311만원");
  });

  it("미달에서 달성으로 바뀌면 그 문구를 보인다", async () => {
    await renderCard(comparison());
    for (const row of scenarioRows()) {
      expect(byTestId(row, "chat-comparison-achievement").map((el) => el.textContent)).toEqual([copy["achievementChange.MISS_TO_ACHIEVE"]]);
    }
  });

  it("달성에서 미달로 바뀌면 그 문구를 보인다", async () => {
    const b = decreased();
    b.diff.byScenario[0].achievementChange = "ACHIEVE_TO_MISS";
    await renderCard(b);
    expect(byTestId(scenarioRows()[0], "chat-comparison-achievement")[0]?.textContent).toBe(copy["achievementChange.ACHIEVE_TO_MISS"]);
  });

  it("(예외) 달성 상태가 그대로면 달성 상태 문구가 없다", async () => {
    await renderCard(decreased());
    for (const row of scenarioRows()) expect(byTestId(row, "chat-comparison-achievement")).toHaveLength(0);
  });

  it("(예외) 바뀐 조건이 0개면 조건 행 없이 '조건이 그대로예요'를 보인다", async () => {
    const b = comparison();
    b.conditions.changed = { ...b.conditions.current };
    b.display["conditions.changed.monthlySavingAmount"] = b.display["conditions.current.monthlySavingAmount"];
    await renderCard(b);
    expect(rows()).toHaveLength(0);
    expect(text("chat-comparison-unchanged")).toBe(copy["comparison.allUnchanged"]);
    expect(text("chat-comparison-title")).toBeNull();
  });

  it("비교 기준 조건을 prop으로 넘기면 그 값과 비교해 고른다", async () => {
    const b = twoChanged();
    // 저축 비중 70은 이미 반영된 기준이면 월 저축액만 바뀐 것이다
    await renderCard(b, { ...b.conditions.current, savingsRatioPct: 70 });
    expect(rowKeys()).toEqual(["monthlySavingAmount"]);
  });
});
