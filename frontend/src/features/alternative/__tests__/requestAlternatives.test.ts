import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../../api/errors";
import periodMock from "../../../mocks/alternative/post-alternatives.200-period.json";
import firstMock from "../../../mocks/alternative/post-alternatives.200.json";
import { requestAlternatives, toAlternativeValues, type AlternativeResponse } from "../requestAlternatives";

const first = firstMock.responses[0].body as unknown as AlternativeResponse;
const period = periodMock.responses[0].body as unknown as AlternativeResponse;

describe("requestAlternatives: A-1 POST /api/alternatives (#258)", () => {
  it("기간 없이는 빈 본문, 기간이 있으면 {targetPeriodMonths}로 부르고 응답을 돌려준다", async () => {
    const request = vi.fn().mockResolvedValue(first);

    expect(await requestAlternatives({ request })).toEqual({ ok: true, response: first });
    expect(request).toHaveBeenLastCalledWith("POST", "/api/alternatives", {});
    await requestAlternatives({ request }, 48);
    expect(request).toHaveBeenLastCalledWith("POST", "/api/alternatives", { targetPeriodMonths: 48 });
  });

  it.each([
    [422, "VALIDATION_FAILED", "periodInvalid"],
    [409, "NO_PLAN", "noPlan"],
    [401, "UNAUTHENTICATED", "unauthenticated"],
    [500, "INTERNAL_ERROR", "failed"],
  ])("%s %s → %s", async (status, code, kind) => {
    const request = vi.fn().mockRejectedValue(ApiError.fromProblem(status, { code, errors: [] }));

    expect(await requestAlternatives({ request }, 48)).toEqual({ ok: false, kind });
  });
});

describe("toAlternativeValues: 카드 본문은 display 문자열 그대로(D-096, A-1-1)", () => {
  it("바뀌는 조건·변화·평균·목표 대비·나빴던 때를 display 키에서 가져오고 비교하기·적용용 조건을 싣는다", () => {
    const values = toAlternativeValues(first);
    const monthly = values.monthlySavingAmount;
    const display = first.alternatives[0].display;

    expect(monthly?.changedCondition).toBe(
      `${display["conditions.current.monthlySavingAmount"]} → ${display["conditions.changed.monthlySavingAmount"]}`,
    );
    expect(monthly?.change).toBe(display["diff.AVG.afterTaxAssetDelta"]);
    expect(monthly?.avgAfterTaxAsset).toBe(display["changed.AVG.afterTaxAsset"]);
    expect(monthly?.gapToGoal).toBe(display["changed.AVG.gapToGoal"]);
    expect(monthly?.badAfterTaxAsset).toBe(`${display["changed.BAD.afterTaxAsset"]} · ${display["changed.BAD.gapToGoal"]}`);
    expect(monthly?.changes).toEqual({ monthlySavingAmount: first.alternatives[0].changes.monthlySavingAmount });
    expect(monthly?.comparison).toEqual({ applicable: true, changedKeys: ["monthlySavingAmount"], display, diff: first.alternatives[0].diff });
    expect(monthly?.volatilityWarning).toBeUndefined();
  });

  it("빈 카드(기한 입력 전)는 값이 없다", () => {
    expect(toAlternativeValues(first).targetPeriodMonths).toBeUndefined();
    expect(toAlternativeValues(period).targetPeriodMonths?.changes).toEqual({ targetPeriodMonths: 48 });
  });

  it("나빴던 때 키가 없으면(ETF 0%) 그 줄은 비운다", () => {
    const card = structuredClone(first.alternatives[0]);
    delete card.display["changed.BAD.afterTaxAsset"];
    const values = toAlternativeValues({ hasAlternatives: true, alternatives: [card] });

    expect(values.monthlySavingAmount?.badAfterTaxAsset).toBeUndefined();
  });
});

describe("달성이 어려운 대안(#343)", () => {
  it("unreachable 카드는 안내용 값으로 옮기고 값이 없는 다른 카드는 비운다", () => {
    const values = toAlternativeValues({
      hasAlternatives: true,
      alternatives: [
        { key: "monthlySavingAmount", changes: {}, diff: null, display: {}, unreachable: true },
        { key: "targetPeriodMonths", changes: {}, diff: null, display: {} },
      ],
    });

    expect(values.monthlySavingAmount).toEqual({ unreachable: true });
    expect(values.targetPeriodMonths).toBeUndefined();
  });
});

describe("달성 판정 옮기기(#352)", () => {
  it("changed의 AVG가 달성이면 achieved, 아니면 false다", () => {
    const base = { changes: { monthlySavingAmount: 1 }, diff: {} as never, display: {} };
    const values = toAlternativeValues({
      hasAlternatives: true,
      alternatives: [
        { key: "monthlySavingAmount", ...base, changed: { scenarios: [{ scenario: "AVG", isAchieved: true }] } },
        { key: "targetPeriodMonths", ...base, changed: { scenarios: [{ scenario: "AVG", isAchieved: false }] } },
        { key: "savingsRatioPct", ...base },
      ],
    });

    expect(values.monthlySavingAmount?.achieved).toBe(true);
    expect(values.targetPeriodMonths?.achieved).toBe(false);
    expect(values.savingsRatioPct?.achieved).toBe(false);
  });
});
