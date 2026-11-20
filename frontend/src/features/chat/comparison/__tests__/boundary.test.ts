// @vitest-environment jsdom
// Issue #53 AC-18·AC-19: 팀 경계(D-069, C12a-6-7 구독 규칙)와 문구 하드코딩 금지(C12b-6)를 소스에서 확인한다.
// 대상은 frontend/src/features/chat/comparison/ 의 구현 소스(테스트 제외)다.
import { describe, expect, it } from "vitest";
import { copy } from "../../__tests__/helpers";
import { comparisonSources } from "./helpers";

const sources = () => comparisonSources();

describe("AC-18 revision 구독 한 곳, A팀 코드 호출 없음, view·settledStatus 읽지 않음 (D-069, C12b-7-4)", () => {
  it("비교 폴더에 구현 소스가 있다", () => {
    expect(sources().length).toBeGreaterThan(0);
  });

  it("store 구독(.subscribe·.onRevisionChange) 참조는 비교 폴더 전체에서 정확히 한 곳이다", () => {
    const all = sources();
    const count = all.reduce((sum, { text }) => sum + (text.match(/\.subscribe\b|\.onRevisionChange\b/g) ?? []).length, 0);
    expect(all.length).toBeGreaterThan(0);
    expect(count).toBe(1);
  });

  it("view·settledStatus를 읽지 않는다(#52·결과 화면 몫)", () => {
    const all = sources();
    expect(all.length).toBeGreaterThan(0);
    for (const { file, text } of all) {
      expect(text, file).not.toMatch(/\bsettledStatus\b/);
      expect(text, file).not.toMatch(/\.view\b/);
      expect(text, file).not.toMatch(/\{[^}]*\bview\b[^}]*\}\s*=\s*[^;]*getState\(\)/);
    }
  });

  it("A팀 코드(입력 features/input, 계획 features/plan)를 import하지 않고 /api/plan을 직접 부르지 않는다(loadPlan·acceptNoPlan만)", () => {
    const all = sources();
    expect(all.length).toBeGreaterThan(0);
    for (const { file, text } of all) {
      expect(text, file).not.toMatch(/from\s+["'][^"']*\/(input|plan)(\/[^"']*)?["']/);
      expect(text, file).not.toMatch(/["'`]\/api\/plan/);
    }
  });
});

describe("AC-19 카드·토스트 문구를 코드에 하드코딩하지 않는다 (C12b-6, 문구표)", () => {
  const keys = [
    "condition.monthlySavingAmount",
    "condition.savingsRatioPct",
    "condition.savingsRateInputPct",
    "condition.targetPeriodMonths",
    "achievementChange.STAYS_ACHIEVED",
    "achievementChange.STAYS_MISSED",
    "achievementChange.MISS_TO_ACHIEVE",
    "achievementChange.ACHIEVE_TO_MISS",
    "toast.applyStaleComparison",
    "toast.applyPlanChanged",
    "toast.applyNotApplicable",
    "toast.applyFailed",
    "comparison.loadFailed",
  ];

  it("문구표 값은 문구표를 옮긴 파일(*copy.ts) 밖의 구현 소스에 글자 그대로 나오지 않는다", () => {
    const all = sources();
    expect(all.length).toBeGreaterThan(0);
    for (const key of keys) expect(copy[key], key).toBeTruthy();
    for (const { file, text } of all.filter(({ file }) => !/copy\.ts$/.test(file))) {
      for (const key of keys) expect(text.includes(copy[key]), `${file}에 ${key} 값이 하드코딩됨`).toBe(false);
    }
  });
});
