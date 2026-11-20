import { useEffect, useState, useSyncExternalStore } from "react";
import { api, planStore } from "../../api";
import { resolveApiMode } from "../../api/client";
import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { AlternativeSection, demoAlternatives } from "../alternative";
import type { AlternativeValues } from "../alternative/types";
import { CompareSection, requestComparison, type ComparisonView, type ConditionChanges } from "../compare";
import { AssumptionsSection } from "./AssumptionsSection";
import { Disclaimer } from "./Disclaimer";
import { GoalBar } from "./GoalBar";
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
  // 비교 영역(#65)은 프론트 상태만 쓴다. 적용 전까지 내 계획은 바뀌지 않는다(FR-CMP-02)
  const [comparison, setComparison] = useState<ComparisonView | null>(null);
  const [comparing, setComparing] = useState(false);
  const [compareError, setCompareError] = useState<string | null>(null);
  // 대안 '비교하기'가 채워 넣는 조건. 바뀔 때마다 비교 영역을 다시 그린다
  const [prefill, setPrefill] = useState<{ seq: number; changes: ConditionChanges } | null>(null);

  async function compare(changes: ConditionChanges) {
    setComparing(true);
    setCompareError(null);
    const result = await requestComparison(
      { mode: resolveApiMode(import.meta.env), request: (method, path) => api.request(method as "GET", path) },
      changes,
    );
    setComparing(false);
    if (result.ok) {
      setComparison(result.view);
      return;
    }
    setComparison(null);
    setCompareError(result.message);
  }

  function resetComparison() {
    setComparison(null);
    setCompareError(null);
    setPrefill(null);
  }

  /** 대안 카드의 '비교하기'. 비교 영역에 조건만 채우고 저장은 하지 않는다(FR-ALT-04, D-046) */
  function prefillComparison(values: AlternativeValues) {
    if (!values.changes) return;
    setComparison(null);
    setCompareError(null);
    setPrefill((prev) => ({ seq: (prev?.seq ?? 0) + 1, changes: values.changes ?? {} }));
  }

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
              {/* 목표 대비 가로 막대(#146). 라벨은 서버 display 문자열을 그대로 쓴다 */}
              <GoalBar
                afterTaxAsset={baseline.afterTaxAsset}
                goalAmount={result.goalAmount}
                goalAmountText={display["goalAmount"]}
                isAchieved={baseline.isAchieved}
              />
            </section>
          )}
          <ScenarioCards scenarios={result.scenarios} display={display} />
          <ResultChart scenarios={result.scenarios} goalAmount={result.goalAmount} display={display} />
          {/* 대안 영역(#67). 기준 시나리오가 미달일 때만 보인다(FR-ALT-01) */}
          <AlternativeSection
            isAchieved={baseline?.isAchieved ?? false}
            values={demoAlternatives(resolveApiMode(import.meta.env))}
            onCompare={prefillComparison}
          />
          {/* 비교 영역(#65). 조건을 바꿔 현재 조건과 나란히 본다 */}
          <CompareSection
            key={prefill?.seq ?? 0}
            initialChanges={prefill?.changes}
            currentDisplay={display}
            comparison={comparison}
            loading={comparing}
            error={compareError}
            onCompare={compare}
            onReset={resetComparison}
          />
        </>
      )}

      <AssumptionsSection assumptions={result.assumptions} display={display} />
      <Disclaimer />
    </div>
  );
}
