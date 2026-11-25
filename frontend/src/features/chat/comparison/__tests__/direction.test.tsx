// @vitest-environment jsdom
// Issue #284: 대화 비교 카드의 바뀐 항목 제목, 조건의 늘림·줄임 표시, 결과(예상 자산·목표 대비)의 변화 방향 색과 차이 값.
// 숫자는 계산 응답(display 문자열·raw 숫자)에서만 가져오고, 방향은 raw 숫자의 크기 비교로만 정한다.
import { cleanup, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, doneMsg, page, userMsg } from "../../__tests__/helpers";
import { DIRECTION_COPY, ETF_LABEL } from "../copy";
import { card, comparison, conditionRows, makeApi, makeStore, mountChat, scenarioRows } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const ID = 1031;
type Body = ReturnType<typeof comparison>;

async function mountWith(body: Body) {
  const api = makeApi({
    F2: [page([userMsg(ID - 1), doneMsg(ID, "RECALC_RESULT", "비교했어요")], false)],
    [`F4 ${ID}`]: [body],
  });
  await mountChat(api, makeStore().store);
}

/** 기본 비교(월 저축액 30만 → 50만, 세 시나리오 모두 자산 증가)를 고쳐 쓴다. */
function body(patch: (b: Body) => void = () => {}, file = "get-comparison.200.json"): Body {
  const b = comparison(file);
  patch(b);
  return b;
}

const tone = (row: HTMLElement, testId: string) => row.querySelector<HTMLElement>(`[data-testid="${testId}-tone"]`);
const text = (row: HTMLElement, testId: string) => row.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.textContent;
const title = () => screen.queryByTestId("chat-comparison-title")?.textContent;

describe("결과의 변화 방향(예상 자산·목표 대비)", () => {
  it("자산이 늘면 긍정 색·▲·'좋아졌어요'이고, 차이 값은 서버 display 그대로다", async () => {
    const b = body();
    await mountWith(b);
    for (const row of scenarioRows(ID)) {
      const scenario = row.getAttribute("data-scenario");
      const asset = tone(row, "chat-comparison-delta");
      expect(asset?.getAttribute("data-direction"), scenario ?? "").toBe("up");
      expect(asset?.className).toContain("text-success");
      expect(asset?.textContent).toContain("▲");
      expect(asset?.textContent).toContain("좋아졌어요");
      expect(text(row, "chat-comparison-delta")).toBe(b.display[`diff.${scenario}.afterTaxAssetDelta`]);
    }
  });

  it("자산이 줄면 부정 색·▼·'나빠졌어요'이다", async () => {
    await mountWith(
      body((b) => {
        for (const item of b.diff.byScenario) item.afterTaxAssetDelta = -item.afterTaxAssetDelta;
      }),
    );
    for (const row of scenarioRows(ID)) {
      const asset = tone(row, "chat-comparison-delta");
      expect(asset?.getAttribute("data-direction")).toBe("down");
      expect(asset?.className).toContain("text-danger");
      expect(asset?.textContent).toContain("▼");
      expect(asset?.textContent).toContain("나빠졌어요");
    }
  });

  it("(예외) 변화량이 0이면 중립 색이고 차이 값은 서버의 '변화 없음' 그대로다", async () => {
    await mountWith(
      body((b) => {
        for (const item of b.diff.byScenario) {
          item.afterTaxAssetDelta = 0;
          b.display[`diff.${item.scenario}.afterTaxAssetDelta`] = "변화 없음";
        }
      }),
    );
    for (const row of scenarioRows(ID)) {
      const asset = tone(row, "chat-comparison-delta");
      expect(asset?.getAttribute("data-direction")).toBe("same");
      expect(asset?.className).toContain("text-text-muted");
      expect(text(row, "chat-comparison-delta")).toBe("변화 없음");
    }
  });

  it("목표 대비 차이 값은 서버 display의 diff.<S>.gapToGoalDelta 그대로다(목표가 그대로면 자산 차이와 같은 문자열, #285)", async () => {
    const b = body();
    await mountWith(b);
    for (const row of scenarioRows(ID)) {
      const scenario = row.getAttribute("data-scenario");
      expect(text(row, "chat-comparison-gap-delta")).toBe(b.display[`diff.${scenario}.gapToGoalDelta`]);
      expect(text(row, "chat-comparison-gap-delta")).toBe(b.display[`diff.${scenario}.afterTaxAssetDelta`]);
      expect(tone(row, "chat-comparison-gap-delta")?.getAttribute("data-direction")).toBe("up");
    }
  });

  it("목표 금액을 바꾼 비교도 서버의 목표 대비 차이 값을 보이고, 방향은 양쪽 목표 대비의 크기로 정한다(#285)", async () => {
    const b = body(() => {}, "get-comparison.200-goal-changed.json");
    await mountWith(b);
    const rows = scenarioRows(ID);
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      const scenario = row.getAttribute("data-scenario");
      const gap = (side: "current" | "changed") =>
        (b[side].scenarios as { scenario: string; gapToGoal: number }[]).find((s) => s.scenario === scenario)?.gapToGoal ?? 0;
      const expected = gap("changed") > gap("current") ? "up" : gap("changed") < gap("current") ? "down" : "same";
      expect(text(row, "chat-comparison-gap-delta")).toBe(b.display[`diff.${scenario}.gapToGoalDelta`]);
      expect(text(row, "chat-comparison-gap-delta")).not.toBe(b.display[`diff.${scenario}.afterTaxAssetDelta`]);
      expect(tone(row, "chat-comparison-gap-delta")?.getAttribute("data-direction")).toBe(expected);
    }
  });

  it("(예외) 목표를 바꿨는데 서버에 목표 대비 차이 키가 없으면 값 없이 방향만 보인다", async () => {
    const b = body((x) => {
      for (const key of Object.keys(x.display)) if (key.endsWith(".gapToGoalDelta")) delete x.display[key];
    }, "get-comparison.200-goal-changed.json");
    await mountWith(b);
    for (const row of scenarioRows(ID)) {
      expect(text(row, "chat-comparison-gap-delta")).toBeUndefined();
      expect(tone(row, "chat-comparison-gap-delta")?.getAttribute("data-direction")).toBeTruthy();
    }
  });
});

describe("바뀐 조건의 늘림·줄임", () => {
  const RAISED: Record<string, number> = {
    monthlySavingAmount: 500_000,
    savingsRatioPct: 70,
    savingsRateInputPct: 4.5,
    targetPeriodMonths: 48,
  };

  it.each(Object.keys(RAISED))("%s를 늘리면 그 행에 ▲·'늘렸어요'가 보이고, 바뀌지 않은 행에는 없다", async (key) => {
    await mountWith(
      body((b) => {
        b.conditions.changed = { ...b.conditions.current, [key]: RAISED[key] };
        b.changedKeys = [key];
      }),
    );
    for (const row of conditionRows(ID)) {
      const mark = row.querySelector('[data-testid="chat-comparison-condition-direction"]');
      if (row.getAttribute("data-key") === key) {
        expect(mark?.getAttribute("data-direction")).toBe("up");
        expect(mark?.textContent).toContain("늘렸어요");
      } else {
        expect(mark).toBeNull();
      }
    }
  });

  it("월 저축액을 줄이면 ▼·'줄였어요'이고, 조건에는 좋고 나쁨 색(긍정·부정)을 쓰지 않는다", async () => {
    await mountWith(
      body((b) => {
        b.conditions.changed = { ...b.conditions.current, monthlySavingAmount: 200_000 };
      }),
    );
    const row = conditionRows(ID).find((r) => r.getAttribute("data-key") === "monthlySavingAmount");
    const mark = row?.querySelector<HTMLElement>('[data-testid="chat-comparison-condition-direction"]');
    expect(mark?.getAttribute("data-direction")).toBe("down");
    expect(mark?.textContent).toContain("줄였어요");
    expect(mark?.className).not.toMatch(/text-(success|danger)/);
  });

  it("목표 금액을 바꾸면 목표 금액 행에도 방향이 보인다", async () => {
    await mountWith(body(() => {}, "get-comparison.200-goal-changed.json"));
    const row = conditionRows(ID).find((r) => r.getAttribute("data-key") === "goalAmount");
    expect(row?.querySelector('[data-testid="chat-comparison-condition-direction"]')).not.toBeNull();
  });
});

describe("카드 제목", () => {
  it("실제로 바뀐 항목만 카드 행 순서대로 나열한다", async () => {
    await mountWith(body((b) => (b.changedKeys = ["targetPeriodMonths", "monthlySavingAmount"])));
    expect(title()).toBe("월 저축액·목표 기간을 바꾼 결과");
  });

  it("조건 하나만 바뀌면 그 항목만 쓴다", async () => {
    await mountWith(body());
    expect(title()).toBe("월 저축액을 바꾼 결과");
  });
});

describe("ETF 종목을 바꾼 비교(D-097)", () => {
  const etf = (b: Body) => {
    b.changedKeys = ["etfProductCode"];
    b.conditions.current = { ...b.conditions.current, etfProductCode: "SP500" };
    b.conditions.changed = { ...b.conditions.current, etfProductCode: "069500" };
    b.display["conditions.current.etfProductName"] = "S&P500 추종 ETF";
    b.display["conditions.changed.etfProductName"] = "코스피200 추종 ETF";
  };

  it("카드가 그려지고 제목은 'ETF 종목', 종목 행은 화면 이름으로 현재 → 바꾼 종목이다", async () => {
    await mountWith(body(etf));
    expect(card(ID)).not.toBeNull();
    expect(title()).toBe("ETF 종목을 바꾼 결과");
    const row = conditionRows(ID).find((r) => r.getAttribute("data-key") === "etfProductCode");
    expect(row?.getAttribute("data-changed")).toBe("true");
    expect(row?.textContent).toContain("S&P500 추종 ETF");
    expect(row?.textContent).toContain("코스피200 추종 ETF");
    expect(row?.querySelector('[data-testid="chat-comparison-condition-direction"]')).toBeNull();
  });

  it("(예외) 종목 화면 이름 display가 없으면 카드를 그리지 않는다", async () => {
    await mountWith(
      body((b) => {
        etf(b);
        delete b.display["conditions.changed.etfProductName"];
      }),
    );
    expect(card(ID)).toBeNull();
  });
});

describe("문구(CP-05)", () => {
  it("제목·방향 문구는 문구표 comparison.*, 종목 라벨은 condition.etfProductCode와 같고 '추천'이 없다", () => {
    for (const [key, value] of Object.entries(DIRECTION_COPY)) {
      expect(value, key).toBe(copy[`comparison.${key}`]);
      expect(value, key).not.toContain("추천");
    }
    expect(ETF_LABEL).toBe(copy["condition.etfProductCode"]);
  });
});
