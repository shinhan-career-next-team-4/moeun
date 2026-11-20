import { afterEach, describe, expect, it } from "vitest";
import { applyMockFilesFromEnv, mockFetch, resetMocks } from "../mock";

describe("VITE_MOCK_FILES로 기본 목 파일 고르기(개발용)", () => {
  afterEach(resetMocks);

  it("값이 없으면 아무것도 고르지 않는다", () => {
    expect(applyMockFilesFromEnv({})).toEqual([]);
    expect(applyMockFilesFromEnv({ VITE_MOCK_FILES: "  " })).toEqual([]);
  });

  it("쉼표로 적은 파일을 모두 고른다", async () => {
    expect(applyMockFilesFromEnv({ VITE_MOCK_FILES: "get-plan.200.json" })).toEqual(["get-plan.200.json"]);

    const response = await mockFetch("/api/plan", { method: "GET" });
    const body = (await response.json()) as { result: { scenarios: { isAchieved: boolean }[] } };
    expect(response.status).toBe(200);
    // 대안 영역이 보이는 미달 계획이다(FR-ALT-01)
    expect(body.result.scenarios.every((scenario) => scenario.isAchieved)).toBe(false);
  });

  it("없는 파일 이름이면 바로 실패한다", () => {
    expect(() => applyMockFilesFromEnv({ VITE_MOCK_FILES: "get-plan.없음.json" })).toThrow();
  });
});
