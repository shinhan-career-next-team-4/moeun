import type { PlanState } from "../../api/planStore";
import { goalName } from "../../shared/units/units";

/** 지금 계획의 목표 이름(조건 바와 같은 규칙, #341). 계획을 모르면 null이다. */
export function planGoalName(state: PlanState): string | null {
  const plan = state.view?.body.plan;
  if (typeof plan !== "object" || plan === null) return null;
  const { goalType, goalCustomName } = plan as { goalType?: unknown; goalCustomName?: unknown };
  if (typeof goalType !== "string") return null;
  return goalName(goalType, typeof goalCustomName === "string" ? goalCustomName : null);
}
