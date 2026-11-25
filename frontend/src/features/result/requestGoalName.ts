import { api, planStore } from "../../api";
import { ApiError } from "../../api/errors";
import type { PlanView } from "../../api/planStore";
import type { ResultPlanBody } from "./types";

/**
 * 결과 화면에서 목표 이름만 바꾼다(#341). 저장은 입력 저장(P-3 `PUT /api/plan`)과 같고, 다른 조건은 지금 계획 값을 그대로
 * 다시 보낸다. 서버는 목표 종류가 `CUSTOM`일 때만 이름을 저장하므로(D-081) 종류도 `CUSTOM`으로 보낸다.
 * 저장하면 결과가 다시 계산되고 되돌리기로 취소할 수 있다.
 */
export type GoalNameOutcome =
  | { kind: "saved" }
  | { kind: "invalid"; reason: "REQUIRED" | "TOO_LONG" }
  | { kind: "planChanged" }
  | { kind: "unauthenticated" }
  | { kind: "failed" };

/** 목표 이름 최대 글자 수(D-074). */
export const GOAL_NAME_MAX_LENGTH = 100;

export async function requestGoalName(plan: ResultPlanBody, name: string): Promise<GoalNameOutcome> {
  const trimmed = name.trim();
  if (trimmed === "") return { kind: "invalid", reason: "REQUIRED" };
  if (trimmed.length > GOAL_NAME_MAX_LENGTH) return { kind: "invalid", reason: "TOO_LONG" };
  const revision = planStore.getState().revision;
  try {
    const view = await api.request<PlanView>("PUT", "/api/plan", {
      expectedRevision: revision,
      goalType: "CUSTOM",
      goalCustomName: trimmed,
      goalAmount: plan.goalAmount,
      targetPeriodMonths: plan.targetPeriodMonths,
      heldAssets: plan.heldAssets,
      monthlySavingAmount: plan.monthlySavingAmount,
      savingsRateInputPct: plan.savingsRateInputPct,
      savingsRatioPct: plan.savingsRatioPct,
    });
    planStore.acceptPlanView(view);
    return { kind: "saved" };
  } catch (caught) {
    if (!(caught instanceof ApiError)) return { kind: "failed" };
    if (caught.code === "UNAUTHENTICATED") return { kind: "unauthenticated" };
    if (caught.code === "PLAN_CHANGED") return { kind: "planChanged" };
    if (caught.code === "VALIDATION_FAILED") return { kind: "invalid", reason: "TOO_LONG" };
    return { kind: "failed" };
  }
}
