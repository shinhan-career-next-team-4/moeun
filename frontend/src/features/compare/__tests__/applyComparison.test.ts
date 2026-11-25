import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../../api/errors";
import { applyComparison } from "../applyComparison";

const changes = { monthlySavingAmount: 1500000 };

function rejectWith(status: number, code: string) {
  return vi.fn().mockRejectedValue(ApiError.fromProblem(status, { code, errors: [] }));
}

describe("applyComparison: P-6 POST /api/plan/apply (#68, C12a-9)", () => {
  it("path COMPARE와 비교 시점 revision, 바꾼 조건을 보내고 새 revision을 돌려준다", async () => {
    const request = vi.fn().mockResolvedValue({ revision: 13 });

    const outcome = await applyComparison({ request }, { expectedRevision: 12, changes });

    expect(request).toHaveBeenCalledWith("POST", "/api/plan/apply", {
      path: "COMPARE",
      expectedRevision: 12,
      conditionChanges: changes,
    });
    expect(outcome).toEqual({ kind: "applied", revision: 13 });
  });

  it("대안 카드 적용은 path ALTERNATIVE로 보낸다(#258)", async () => {
    const request = vi.fn().mockResolvedValue({ revision: 13 });

    await applyComparison({ request }, { expectedRevision: 12, changes: { targetPeriodMonths: 48 }, path: "ALTERNATIVE" });

    expect(request).toHaveBeenCalledWith("POST", "/api/plan/apply", {
      path: "ALTERNATIVE",
      expectedRevision: 12,
      conditionChanges: { targetPeriodMonths: 48 },
    });
  });

  it("200이어도 정수 revision이 없으면 실패로 본다", async () => {
    const outcome = await applyComparison({ request: vi.fn().mockResolvedValue({}) }, { expectedRevision: 12, changes });

    expect(outcome).toEqual({ kind: "failed" });
  });

  it.each([
    [409, "PLAN_CHANGED", "planChanged"],
    [409, "NO_PLAN", "noPlan"],
    [422, "VALIDATION_FAILED", "notApplicable"],
    [422, "FIELD_NOT_CHANGEABLE", "notApplicable"],
    [401, "UNAUTHENTICATED", "unauthenticated"],
    [500, "INTERNAL_ERROR", "failed"],
    [400, "MALFORMED_REQUEST", "failed"],
  ])("%s %s → %s", async (status, code, kind) => {
    const outcome = await applyComparison({ request: rejectWith(status, code) }, { expectedRevision: 12, changes });

    expect(outcome).toEqual({ kind });
  });

  it("네트워크 실패는 failed다(재시도 가능)", async () => {
    const outcome = await applyComparison(
      { request: vi.fn().mockRejectedValue(ApiError.network()) },
      { expectedRevision: 12, changes },
    );

    expect(outcome).toEqual({ kind: "failed" });
  });
});
