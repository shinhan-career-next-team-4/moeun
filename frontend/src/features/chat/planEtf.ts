import type { PlanState } from "../../api/planStore";

/** 계획이 ETF 0%(적용된 적금 비중 100)인지. 계획을 모르면 false라 근거를 숨기지 않는다. */
export function planHasNoEtf(state: PlanState): boolean {
  const assumptions = state.view?.body.result.assumptions;
  if (typeof assumptions !== "object" || assumptions === null) return false;
  return (assumptions as { savingsRatioPct?: unknown }).savingsRatioPct === 100;
}
