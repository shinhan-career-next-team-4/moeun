import { describe, expect, it } from "vitest";
import { buildMockProjection, buildMockScenarioCards, MOCK_PROJECTION } from "../mockProjection";
import type { ProjectionScenario } from "../types";

const SCENARIOS: ProjectionScenario[] = ["GOOD", "AVG", "BAD"];

describe("mock 데이터 mockProjection (#298)", () => {
  it.each(SCENARIOS)("%s 시나리오는 1~120개월을 빠짐없이 순서대로 담는다", (scenario) => {
    const months = MOCK_PROJECTION[scenario].map((point) => point.month);

    expect(months).toEqual(Array.from({ length: 120 }, (_, i) => i + 1));
  });

  it.each(SCENARIOS)("%s 시나리오의 합계·수익 합이 층의 합과 맞다(서버가 이렇게 준다는 가정)", (scenario) => {
    for (const p of MOCK_PROJECTION[scenario]) {
      expect(p.profit).toBe(p.savingsInterest + p.etfGain);
      expect(p.total).toBe(p.principal + p.profit);
    }
  });

  it.each(SCENARIOS)("%s 시나리오의 값은 모두 정수이고 달이 갈수록 줄지 않는다", (scenario) => {
    const points = MOCK_PROJECTION[scenario];

    points.forEach((p, i) => {
      for (const value of [p.principal, p.savingsInterest, p.etfGain, p.profit, p.total]) {
        expect(Number.isInteger(value)).toBe(true);
      }
      if (i > 0) {
        expect(p.total).toBeGreaterThanOrEqual(points[i - 1].total);
        expect(p.principal).toBeGreaterThanOrEqual(points[i - 1].principal);
      }
    });
  });

  it("원금은 세 시나리오가 같고 120개월째 6,000만원이다", () => {
    for (const scenario of SCENARIOS) {
      expect(MOCK_PROJECTION[scenario][119].principal).toBe(60_000_000);
    }
  });

  it("120개월째 합계는 나빴던 때 < 평균 < 좋았던 때 순이다", () => {
    const last = (s: ProjectionScenario) => MOCK_PROJECTION[s][119].total;

    expect(last("BAD")).toBeLessThan(last("AVG"));
    expect(last("AVG")).toBeLessThan(last("GOOD"));
  });
});

describe("기간을 고르는 mock buildMockProjection (#298, 30년까지)", () => {
  it.each([10, 20, 30])("%i년이면 시나리오마다 %i × 12개월을 담는다", (years) => {
    const data = buildMockProjection(years);

    for (const scenario of SCENARIOS) {
      expect(data[scenario]).toHaveLength(years * 12);
      expect(data[scenario].at(-1)?.month).toBe(years * 12);
    }
  });

  it("30년이면 원금이 1억 8,000만원이고 합계·수익 합 규칙을 그대로 지킨다", () => {
    const data = buildMockProjection(30);

    for (const scenario of SCENARIOS) {
      expect(data[scenario][359].principal).toBe(180_000_000);
      for (const p of data[scenario]) {
        expect(p.profit).toBe(p.savingsInterest + p.etfGain);
        expect(p.total).toBe(p.principal + p.profit);
      }
    }
  });
});

describe("3구간 카드용 mock buildMockScenarioCards (#298)", () => {
  const { scenarios, display } = buildMockScenarioCards(MOCK_PROJECTION);

  it("좋았던 때·평균·나빴던 때 세 개를 담고 마지막 달 합계가 카드 금액이다", () => {
    expect(scenarios.map((s) => s.scenario)).toEqual(["GOOD", "AVG", "BAD"]);
    for (const s of scenarios) {
      expect(s.afterTaxAsset).toBe(MOCK_PROJECTION[s.scenario][119].total);
    }
  });

  it("카드가 읽는 표시 문자열 키가 모두 있다(서버 display와 같은 키)", () => {
    for (const code of SCENARIOS) {
      expect(display[`${code}.afterTaxAsset`]).toMatch(/^약 [\d,]+만원$/);
      expect(display[`${code}.gapToGoal`]).toMatch(/(달성|미달)$/);
    }
  });

  it("목표를 넘은 시나리오는 도달 월이 있고, 못 넘은 시나리오는 없다", () => {
    for (const s of scenarios) {
      expect(s.isAchieved).toBe(s.gapToGoal >= 0);
      expect(s.goalReachMonth !== null).toBe(s.isAchieved);
      expect(display[`${s.scenario}.goalReachMonth`] !== undefined).toBe(s.isAchieved);
    }
    expect(scenarios.some((s) => s.isAchieved)).toBe(true);
    expect(scenarios.some((s) => !s.isAchieved)).toBe(true);
  });
});
