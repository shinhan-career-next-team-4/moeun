import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { api, planStore } from "../../api";
import { resolveApiMode } from "../../api/client";
import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { AlternativeSection, demoAlternatives } from "../alternative";
import type { AlternativeValues } from "../alternative/types";
import {
  applyComparison,
  CompareSection,
  ConfirmApplyDialog,
  requestComparison,
  type ComparisonView,
  type ConditionChanges,
} from "../compare";
import { AssumptionsSection } from "./AssumptionsSection";
import { Disclaimer } from "./Disclaimer";
import { GoalBar } from "./GoalBar";
import { PlanConditionBar } from "./PlanConditionBar";
import { ResultLayout } from "./ResultLayout";
import { ResultChart } from "./ResultChart";
import { ScenarioCards } from "./ScenarioCards";
import { UndoToast } from "./UndoToast";
import type { ResultPlanView } from "./types";

const subscribe = (listener: () => void) => planStore.subscribe(listener);
const getSnapshot = () => planStore.getState();

/** 결과 화면(#49·#50, C12a-7). 모든 숫자 문자열은 display를 그대로 쓴다. */
export function ResultPage() {
  const { view, revision } = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const [loadError, setLoadError] = useState(false);
  // 비교 영역(#65)은 프론트 상태만 쓴다. 적용 전까지 내 계획은 바뀌지 않는다(FR-CMP-02)
  const [comparison, setComparison] = useState<ComparisonView | null>(null);
  const [comparing, setComparing] = useState(false);
  const [compareError, setCompareError] = useState<string | null>(null);
  // 대안 '비교하기'가 채워 넣는 조건. 바뀔 때마다 비교 영역을 다시 그린다
  const [prefill, setPrefill] = useState<{ seq: number; changes: ConditionChanges } | null>(null);
  // 지금 보이는 비교를 만든 조건과 그때의 revision. 내 계획이 바뀌면 그 비교는 적용할 수 없다(C12a-9-2)
  const [compared, setCompared] = useState<{ changes: ConditionChanges; revision: number } | null>(null);
  // 적용 확인창(#68). 적용에 성공하면 비교 영역을 비우려고 seq를 올려 다시 그린다
  const [confirming, setConfirming] = useState<ConditionChanges | null>(null);
  const [applying, setApplying] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [applyFeedback, setApplyFeedback] = useState<string | null>(null);
  const [resetSeq, setResetSeq] = useState(0);
  // 되돌리기 토스트(#51). 적용마다 id를 올려 이전 토스트의 늦은 종료가 새 토스트를 닫지 못하게 한다
  const [undo, setUndo] = useState<{ revision: number; id: number } | null>(null);
  const dismissUndo = useCallback(() => setUndo(null), []);

  async function compare(changes: ConditionChanges) {
    const comparedAt = planStore.getState().revision;
    setComparing(true);
    setCompareError(null);
    setApplyFeedback(null);
    const result = await requestComparison(
      { mode: resolveApiMode(import.meta.env), request: (method, path, body) => api.request(method, path, body) },
      changes,
    );
    setComparing(false);
    if (result.ok) {
      setComparison(result.view);
      setCompared(comparedAt === null ? null : { changes, revision: comparedAt });
      return;
    }
    setComparison(null);
    setCompared(null);
    setCompareError(result.message);
  }

  function resetComparison() {
    setComparison(null);
    setCompared(null);
    setCompareError(null);
    setPrefill(null);
  }

  function openConfirm(changes: ConditionChanges) {
    setConfirmError(null);
    setConfirming(changes);
  }

  function closeConfirm() {
    setConfirming(null);
    setConfirmError(null);
  }

  /** P-6 적용(#68, C12a-9-2). 코드별 동작은 대화 적용(F-5, C12b-7-2)과 같다 */
  async function confirmApply() {
    if (confirming === null || compared === null || applying) return;
    const sent = compared.revision;
    setApplying(true);
    setConfirmError(null);
    const outcome = await applyComparison(
      { request: (method, path, body) => api.request(method, path, body) },
      { expectedRevision: sent, changes: confirming },
    );
    setApplying(false);
    switch (outcome.kind) {
      case "applied":
        closeConfirm();
        resetComparison();
        setResetSeq((seq) => seq + 1);
        planStore.acceptRevision(outcome.revision);
        setUndo((prev) => ({ revision: outcome.revision, id: (prev?.id ?? 0) + 1 }));
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return;
      case "planChanged":
        closeConfirm();
        setApplyFeedback(COPY["toast.applyPlanChanged"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return;
      case "noPlan":
        closeConfirm();
        planStore.acceptNoPlan(sent);
        return;
      case "notApplicable":
        closeConfirm();
        setCompared(null);
        setApplyFeedback(COPY["toast.applyNotApplicable"]);
        return;
      case "unauthenticated":
        closeConfirm();
        return;
      case "failed":
        setConfirmError(COPY["toast.applyFailed"]);
    }
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

  // W-03: 원금·수익 분해. 두 display 키가 모두 있을 때만 보여 준다(프론트가 숫자를 만들지 않는다).
  const principal = baselineResultScenario ? display[`${baselineResultScenario}.principal`] : undefined;
  const afterTaxGain = baselineResultScenario ? display[`${baselineResultScenario}.afterTaxGain`] : undefined;
  const principalAndGain =
    principal && afterTaxGain
      ? COPY["result.principalAndGain"].replace("{principal}", principal).replace("{gain}", afterTaxGain)
      : null;

  const exceededMessage = COPY["result.alreadyExceeded"]
    .replace("{heldAssets}", display["conditions.heldAssets"] ?? "")
    .replace("{goalAmount}", display["goalAmount"] ?? "");

  return (
    <ResultLayout bar={<PlanConditionBar view={data} />}>
      <div className="mx-auto flex max-w-5xl flex-col gap-6 px-6 py-8">
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
                <p
                  data-testid="result-gap-badge"
                  className={`mt-2 inline-block rounded-full px-3 py-1 text-sm font-semibold ${
                    baseline.isAchieved ? "bg-success-soft text-success" : "bg-warning-soft text-warning"
                  }`}
                >
                  {display[`${baselineResultScenario}.gapBadge`] ?? display[`${baselineResultScenario}.gapToGoal`]}
                </p>
                {principalAndGain && <p className="mt-2 text-sm text-text-muted">{principalAndGain}</p>}
                {/* 목표 대비 가로 막대(#146). 라벨은 서버 display 문자열을 그대로 쓴다 */}
                <GoalBar
                  afterTaxAsset={baseline.afterTaxAsset}
                  goalAmount={result.goalAmount}
                  goalAmountText={display["goalAmount"]}
                  isAchieved={baseline.isAchieved}
                />
              </section>
            )}
            <ScenarioCards
              scenarios={result.scenarios}
              display={display}
              scenarioMethod={result.assumptions.scenarioMethod}
            />
            <ResultChart scenarios={result.scenarios} goalAmount={result.goalAmount} display={display} />
            {/* 대안 영역(#67). 기준 시나리오가 미달일 때만 보인다(FR-ALT-01) */}
            <AlternativeSection
              isAchieved={baseline?.isAchieved ?? false}
              values={demoAlternatives(resolveApiMode(import.meta.env))}
              onCompare={prefillComparison}
            />
            {/* 비교 영역(#65). 조건을 바꿔 현재 조건과 나란히 본다 */}
            <CompareSection
              key={`${prefill?.seq ?? 0}-${resetSeq}`}
              initialChanges={prefill?.changes}
              currentDisplay={display}
              comparison={comparison}
              loading={comparing}
              error={compareError}
              onCompare={compare}
              onReset={resetComparison}
              comparedChanges={compared?.changes ?? null}
              applyEnabled={compared !== null && compared.revision === revision}
              onApply={openConfirm}
            />
            {applyFeedback && (
              <p role="status" className="text-sm text-text-muted">
                {applyFeedback}
              </p>
            )}
          </>
        )}

        <AssumptionsSection assumptions={result.assumptions} display={display} />
        <Disclaimer />
      </div>
      {confirming !== null && comparison !== null && (
        <ConfirmApplyDialog
          comparison={comparison}
          applying={applying}
          error={confirmError}
          onCancel={closeConfirm}
          onConfirm={() => void confirmApply()}
        />
      )}
      <UndoToast key={undo?.id ?? 0} appliedRevision={undo?.revision ?? null} onDismiss={dismissUndo} onFeedback={setApplyFeedback} />
    </ResultLayout>
  );
}
