import { describe, expect, it } from "vitest";
import { MOCK_PROJECTION } from "../mockProjection";
import type { ProjectionScenario } from "../types";

const SCENARIOS: ProjectionScenario[] = ["LOW", "BASE", "HIGH"];

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

  it("120개월째 합계는 하위 10% < 기준 < 상위 10% 순이다", () => {
    const last = (s: ProjectionScenario) => MOCK_PROJECTION[s][119].total;

    expect(last("LOW")).toBeLessThan(last("BASE"));
    expect(last("BASE")).toBeLessThan(last("HIGH"));
  });
});
