"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

import { copy } from "@/copy/ko";
import { useSession } from "@/features/auth";
import { getInputRules } from "@/lib/api/endpoints";

import { Disclaimer } from "./Disclaimer";
import { PlanConditionBar } from "./PlanConditionBar";
import { PlanProvider, usePlan } from "./PlanProvider";

/** 결과 화면 뼈대: 조건 바 + 결과 본문 자리(계산 담당) + 면책 문구 */
export function ResultScreen() {
  return (
    <PlanProvider>
      <ResultLayout />
    </PlanProvider>
  );
}

function ResultLayout() {
  const router = useRouter();
  const welcome = useSearchParams().get("welcome") === "1";
  const { loginId } = useSession();
  const { plan, loaded } = usePlan();
  const [baseRatio, setBaseRatio] = useState<number | null>(null);

  useEffect(() => {
    getInputRules()
      .then((rules) => setBaseRatio(rules.baseSavingsRatioPct))
      .catch(() => setBaseRatio(null));
  }, []);

  useEffect(() => {
    if (loaded && plan === null) {
      router.replace("/input");
    }
  }, [loaded, plan, router]);

  if (!loaded || plan === null || baseRatio === null) {
    return null;
  }
  return (
    <div className="flex flex-1 flex-col gap-6">
      {welcome && <p className="text-sm font-medium text-primary-strong">{copy.plan.welcome(loginId)}</p>}
      <PlanConditionBar plan={plan} baseSavingsRatioPct={baseRatio} />
      <section aria-label="결과" className="flex min-h-64 flex-1 items-center justify-center rounded-xl border border-dashed border-border bg-surface text-sm text-text-muted">
        {copy.plan.resultPending}
      </section>
      <Disclaimer />
    </div>
  );
}
