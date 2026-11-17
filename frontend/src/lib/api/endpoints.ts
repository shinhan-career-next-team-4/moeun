import { ApiError, apiFetch } from "./client";
import type { ApplyRequest, InputRules, MeResponse, PlanInputDto, PlanView } from "./types";

export function login(loginId: string, password: string): Promise<MeResponse> {
  return apiFetch("/api/auth/login", { method: "POST", body: { loginId, password }, skipAuthRedirect: true });
}

export function logout(): Promise<void> {
  return apiFetch("/api/auth/logout", { method: "POST", skipAuthRedirect: true });
}

export function me(): Promise<MeResponse> {
  return apiFetch("/api/auth/me", { skipAuthRedirect: true });
}

/** 계획이 없으면 null */
export async function getPlan(): Promise<PlanView | null> {
  try {
    return await apiFetch<PlanView>("/api/plan");
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

export function savePlan(input: PlanInputDto): Promise<PlanView> {
  return apiFetch("/api/plan", { method: "POST", body: input });
}

export function applyPlan(request: ApplyRequest): Promise<PlanView> {
  return apiFetch("/api/plan/apply", { method: "POST", body: request });
}

export function undoPlan(expectedRevision: number): Promise<PlanView> {
  return apiFetch("/api/plan/undo", { method: "POST", body: { expectedRevision } });
}

export function getInputRules(): Promise<InputRules> {
  return apiFetch("/api/input-rules");
}
