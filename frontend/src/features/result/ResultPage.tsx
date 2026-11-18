import { useEffect, useState, useSyncExternalStore } from "react";
import { planStore } from "../../api";
import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { AssumptionsSection } from "./AssumptionsSection";
import { Disclaimer } from "./Disclaimer";
import { PlanConditionBar } from "./PlanConditionBar";
import { ResultChart } from "./ResultChart";
import { ScenarioCards } from "./ScenarioCards";
import type { ResultPlanView } from "./types";

const subscribe = (listener: () => void) => planStore.subscribe(listener);
const getSnapshot = () => planStore.getState();

/** 결과 화면(#49·#50, C12a-7). 모든 숫자 문자열은 display를 그대로 쓴다. */
export function ResultPage() {
  const { view } = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const [loadError, setLoadError] = useState(false);

  function load(fresh = false) {
    setLoadError(false);
    planStore.loadPlan({ fresh }).catch((caught) => {
      if (caught instanceof ApiError && (caught.code === "UNAUTHENTICATED" || caught.code === "NO_PLAN")) return;
      setLoadError(true);
    });
  }

  useEffect(() => {
    if (planStore.getState().view === null) {
      let active = true;
      planStore.loadPlan().catch((caught) => {
        if (!active) return;
        if (caught instanceof ApiError && (caught.code === "UNAUTHENTICATED" || caught.code === "NO_PLAN")) return;
        setLoadError(true);
      });
      return () => {
        active = false;
      };
    }
  }, []);

  if (loadError && view === null) {
    return (
      <div className="mx-auto flex max-w-5xl flex-col items-start gap-3 px-6 py-10">
        <p role="alert" className="text-sm text-danger">
          {COPY["result.loadFailed"]}
        </p>
        <Button variant="secondary" onClick={() => load(true)}>
          다시 시도
        </Button>
      </div>
    );
  }

  if (view === null) return null;

  const data = view.body as ResultPlanView;
  const { result, display, baselineResultScenario } = data;
  const isExceeded = result.status === "ALREADY_EXCEEDED";
  const baseline = baselineResultScenario
    ? result.scenarios.find((s) => s.scenario === baselineResultScenario)
    : undefined;

  const exceededMessage = COPY["result.alreadyExceeded"]
    .replace("{heldAssets}", display["conditions.heldAssets"] ?? "")
    .replace("{goalAmount}", display["goalAmount"] ?? "");

  return (
    <div className="mx-auto flex min-h-[calc(100vh-3.5rem)] max-w-5xl flex-col gap-6 px-6 py-8">
      <PlanConditionBar view={data} />

      {isExceeded ? (
        <section
          aria-label="목표 초과 안내"
          className="rounded-xl border border-primary bg-primary-soft p-6 text-sm text-primary-strong"
        >
          <p className="font-semibold">{exceededMessage}</p>
        </section>
      ) : (
        <>
          {baselineResultScenario && baseline && (
            <section aria-label="결과 요약" className="rounded-xl border border-border bg-surface p-6 shadow-sm">
              <p className="text-sm font-medium text-text-muted">
                {COPY[`scenario.${baselineResultScenario}`]} · {display["targetMonth"]} 기준
              </p>
              <p className="mt-1 text-2xl font-bold text-text">{display[`${baselineResultScenario}.afterTaxAsset`]}</p>
              <p className={`mt-1 text-sm font-medium ${baseline.isAchieved ? "text-success" : "text-danger"}`}>
                {display[`${baselineResultScenario}.gapToGoal`]}
              </p>
            </section>
          )}
          <ScenarioCards scenarios={result.scenarios} display={display} />
          <ResultChart scenarios={result.scenarios} goalAmount={result.goalAmount} display={display} />
        </>
      )}

      <AssumptionsSection assumptions={result.assumptions} display={display} />
      <Disclaimer />
    </div>
  );
}
