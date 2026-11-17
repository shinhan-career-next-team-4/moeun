"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

import { Toast } from "@/components/ui";
import { copy } from "@/copy/ko";
import { ApiError } from "@/lib/api/client";
import { applyPlan, getPlan, undoPlan } from "@/lib/api/endpoints";
import type { ChangeSource, ConditionOverride, PlanView } from "@/lib/api/types";

export type ApplyResult = "applied" | "conflict" | "invalid" | "busy" | "error";
export type UndoResult = "undone" | "conflict" | "busy" | "error";

interface PlanContextValue {
  plan: PlanView | null;
  loaded: boolean;
  applying: boolean;
  /** 바꾼 조건을 내 계획으로 적용(FR-PLAN-03). 확인창 시점의 revision을 함께 보낸다(PR-08) */
  apply: (request: { source: ChangeSource; changes: ConditionOverride }) => Promise<ApplyResult>;
  /** 직전 계획으로 되돌리기(FR-PLAN-04) */
  undo: () => Promise<UndoResult>;
}

const PlanContext = createContext<PlanContextValue | null>(null);

type Notice = "applied" | "undone" | "conflict" | null;
type RunResult = "done" | "conflict" | "invalid" | "busy" | "error";

/** 결과 화면의 내 계획 상태. 조건 바, 결과 본문(계산 담당), 대화(B팀)가 같은 상태를 본다. */
export function PlanProvider({ children }: { children: ReactNode }) {
  const [plan, setPlan] = useState<PlanView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [applying, setApplying] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const inFlight = useRef(false);
  const planRef = useRef<PlanView | null>(null);

  useEffect(() => {
    planRef.current = plan;
  }, [plan]);

  useEffect(() => {
    getPlan()
      .then(setPlan)
      .catch(() => setPlan(null))
      .finally(() => setLoaded(true));
  }, []);

  /** 적용·되돌리기 공통: 동시에 하나만 보내고, 409면 서버가 준 최신 계획으로 바꾼다 */
  const run = useCallback(async (action: (current: PlanView) => Promise<PlanView>, success: "applied" | "undone"): Promise<RunResult> => {
    const current = planRef.current;
    if (inFlight.current || current === null) {
      return "busy";
    }
    inFlight.current = true;
    setApplying(true);
    try {
      const next = await action(current);
      planRef.current = next;
      setPlan(next);
      setNotice(success);
      return "done";
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        const latest = error.plan ?? current;
        planRef.current = latest;
        setPlan(latest);
        setNotice("conflict");
        return "conflict";
      }
      return error instanceof ApiError && error.status === 400 ? "invalid" : "error";
    } finally {
      inFlight.current = false;
      setApplying(false);
    }
  }, []);

  const apply = useCallback(
    async (request: { source: ChangeSource; changes: ConditionOverride }): Promise<ApplyResult> => {
      const result = await run((current) => applyPlan({ ...request, expectedRevision: current.revision }), "applied");
      return result === "done" ? "applied" : result;
    },
    [run],
  );
  const undo = useCallback(async (): Promise<UndoResult> => {
    const result = await run((current) => undoPlan(current.revision), "undone");
    return result === "done" ? "undone" : result === "invalid" ? "error" : result;
  }, [run]);
  const closeNotice = useCallback(() => setNotice(null), []);

  return (
    <PlanContext.Provider value={{ plan, loaded, applying, apply, undo }}>
      {children}
      {notice === "applied" && <Toast message={copy.plan.applied} actionLabel={copy.plan.undo} onAction={() => void undo()} onClose={closeNotice} />}
      {notice === "undone" && <Toast message={copy.plan.undone} onClose={closeNotice} />}
      {notice === "conflict" && <Toast message={copy.plan.conflict} onClose={closeNotice} />}
    </PlanContext.Provider>
  );
}

export function usePlan(): PlanContextValue {
  const value = useContext(PlanContext);
  if (value === null) {
    throw new Error("usePlan은 PlanProvider 안에서만 쓸 수 있어요.");
  }
  return value;
}
