// 문구표(copy/copy.ko.yml)를 옮긴 상수가 파일과 같은지 확인한다(I-3).
import { describe, expect, it } from "vitest";
import { copy } from "../../__tests__/helpers";

// 제품 모듈은 테스트 안에서 불러온다. 파일 맨 위 import가 실패하면 노드가 수집되지 않아 단언 실패로 보이지 않는다.
const loadCopy = () => import("../copy");

describe("비교 카드 문구 상수는 copy/copy.ko.yml과 같다", () => {
  it("condition.* 4개", async () => {
    const { CONDITION_LABELS } = await loadCopy();
    expect(Object.keys(CONDITION_LABELS)).toHaveLength(4);
    for (const [key, value] of Object.entries(CONDITION_LABELS)) expect(value, key).toBe(copy[`condition.${key}`]);
  });

  it("목표 금액 행 라벨은 condition.goalAmount (D-090)", async () => {
    const { GOAL_LABEL } = await loadCopy();
    expect(GOAL_LABEL).toBe(copy["condition.goalAmount"]);
  });

  it("목표 이름 행 라벨은 condition.goalCustomName (#345)", async () => {
    const { GOAL_NAME_LABEL } = await loadCopy();
    expect(GOAL_NAME_LABEL).toBe(copy["condition.goalCustomName"]);
  });

  it("achievementChange.* 4개", async () => {
    const { ACHIEVEMENT_CHANGE_LABELS } = await loadCopy();
    expect(Object.keys(ACHIEVEMENT_CHANGE_LABELS)).toHaveLength(4);
    for (const [key, value] of Object.entries(ACHIEVEMENT_CHANGE_LABELS)) expect(value, key).toBe(copy[`achievementChange.${key}`]);
  });

  it("toast.apply* 4개와 comparison.loadFailed", async () => {
    const { LOAD_FAILED, TOAST_COPY } = await loadCopy();
    expect(Object.keys(TOAST_COPY)).toHaveLength(4);
    for (const [key, value] of Object.entries(TOAST_COPY)) expect(value, key).toBe(copy[`toast.${key}`]);
    expect(LOAD_FAILED).toBe(copy["comparison.loadFailed"]);
  });

  it("comparison.applyButton·comparison.previousResult (AC-30)", async () => {
    const { APPLY_LABEL, COLLAPSED_LABEL } = await loadCopy();
    expect(copy["comparison.applyButton"]).toBe("내 계획으로 적용");
    expect(copy["comparison.previousResult"]).toBe("이전 결과");
    expect(APPLY_LABEL).toBe(copy["comparison.applyButton"]);
    expect(COLLAPSED_LABEL).toBe(copy["comparison.previousResult"]);
  });
});
