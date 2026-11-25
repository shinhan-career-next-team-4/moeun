import { useEffect, useState, useSyncExternalStore } from "react";
import { api, planStore } from "../../api";
import { resolveApiMode } from "../../api/client";
import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { AlternativeSection, requestAlternatives, toAlternativeValues } from "../alternative";
import type { AlternativeValues, AlternativeValuesByKey } from "../alternative/types";
import {
  applyComparison,
  type ApplyOutcome,
  CompareSection,
  ConfirmApplyDialog,
  requestComparison,
  type ComparisonView,
  type ConditionChanges,
} from "../compare";
import { ProjectionChart, type ProjectionScenario } from "../projection";
import { Disclaimer } from "./Disclaimer";
import { GoalBar } from "./GoalBar";
import { PlanConditionBar } from "./PlanConditionBar";
import { ResultLayout } from "./ResultLayout";
// 결과 화면에서 숨김(#404). 다시 보이려면 이 import와 아래 JSX의 주석을 푼다
// import { EtfRiskNotice } from "./EtfRiskNotice";
import { ScenarioCards } from "./ScenarioCards";
import type { ResultPlanView } from "./types";

const subscribe = (listener: () => void) => planStore.subscribe(listener);
const getSnapshot = () => planStore.getState();

/** 결과 화면(#49·#50, C12a-7). 모든 숫자 문자열은 display를 그대로 쓴다. */
export function ResultPage() {
  const { view, revision } = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const [loadError, setLoadError] = useState(false);
  // 3구간 카드로 고른 시나리오(#315). 비어 있으면 기준 시나리오를 그린다
  const [pickedScenario, setPickedScenario] = useState<ProjectionScenario | null>(null);
  // 비교 영역(#65)은 프론트 상태만 쓴다. 적용 전까지 내 계획은 바뀌지 않는다(FR-CMP-02)
  const [comparison, setComparison] = useState<ComparisonView | null>(null);
  const [comparing, setComparing] = useState(false);
  const [compareError, setCompareError] = useState<string | null>(null);
  // 대안 '비교하기'가 채워 넣는 조건. 바뀔 때마다 비교 영역을 다시 그린다
  const [prefill, setPrefill] = useState<{ seq: number; changes: ConditionChanges } | null>(null);
  // 지금 보이는 비교를 만든 조건과 그때의 revision. 내 계획이 바뀌면 그 비교는 적용할 수 없다(C12a-9-2)
  const [compared, setCompared] = useState<{ changes: ConditionChanges; revision: number } | null>(null);
  // 적용 확인창(#68). 연 순간의 비교·조건·revision을 고정해 보여 주는 값과 보내는 값을 같게 한다.
  // 적용에 성공하면 비교 영역을 비우려고 seq를 올려 다시 그린다
  const [confirming, setConfirming] = useState<{
    comparison: ComparisonView;
    changes: ConditionChanges;
    revision: number;
    path: "COMPARE" | "ALTERNATIVE";
  } | null>(null);
  // 대안 카드(#258, A-1). 받은 시점의 revision이 지금과 같을 때만 적용할 수 있다
  const [alternatives, setAlternatives] = useState<{
    revision: number;
    hasAlternatives: boolean;
    values: AlternativeValuesByKey;
    /** 적용이 422로 거절됐다. 다시 받을 때까지 대안 적용을 막는다 */
    blocked: boolean;
  } | null>(null);
  const [alternativesFailed, setAlternativesFailed] = useState(false);
  const [applying, setApplying] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [applyFeedback, setApplyFeedback] = useState<string | null>(null);
  const [resetSeq, setResetSeq] = useState(0);

  async function compare(changes: ConditionChanges) {
    const comparedAt = planStore.getState().revision;
    setConfirming(null);
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
    setConfirming(null);
    setComparison(null);
    setCompared(null);
    setCompareError(null);
    setPrefill(null);
  }

  /** 대안 카드의 '내 계획으로 적용'(#258). 확인창을 거쳐 P-6 ALTERNATIVE로 적용한다 */
  function openAlternativeConfirm(values: AlternativeValues) {
    if (alternatives === null || !values.comparison || !values.changes) return;
    setConfirmError(null);
    setConfirming({
      comparison: values.comparison,
      changes: values.changes,
      revision: alternatives.revision,
      path: "ALTERNATIVE",
    });
  }

  function openConfirm() {
    if (comparison === null || compared === null) return;
    setConfirmError(null);
    setConfirming({ comparison, changes: compared.changes, revision: compared.revision, path: "COMPARE" });
  }

  function closeConfirm() {
    setConfirming(null);
    setConfirmError(null);
  }

  /**
   * P-6 응답을 화면 동작으로 바꾼다(#68, C12a-9-2). 코드별 동작은 대화 적용(F-5, C12b-7-2)과 같다.
   * 적용에 성공하면 새 revision을, 아니면 null을 돌려준다. 다시 시도할 수 있는 실패의 안내 자리는
   * 부르는 쪽이 정한다(확인창 안 / 확인창 없는 적용은 토스트).
   */
  function settleApply(
    outcome: ApplyOutcome,
    { revision: sent, path }: { revision: number; changes: ConditionChanges; path: "COMPARE" | "ALTERNATIVE" },
    onFailed: () => void,
  ): number | null {
    switch (outcome.kind) {
      case "applied":
        closeConfirm();
        resetComparison();
        setResetSeq((seq) => seq + 1);
        // 요청 중에 계획 상태가 비워졌으면(다른 계정 로그인 등) 다시 채우지 않는다(대화 적용 onApplied와 같다)
        if (planStore.getState().revision === null) return null;
        planStore.acceptRevision(outcome.revision);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return outcome.revision;
      case "planChanged":
        closeConfirm();
        setApplyFeedback(COPY["toast.applyPlanChanged"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return null;
      case "noPlan":
        closeConfirm();
        planStore.acceptNoPlan(sent);
        return null;
      case "notApplicable":
        closeConfirm();
        // 거절된 쪽만 다시 받을/비교할 때까지 막는다
        if (path === "ALTERNATIVE") setAlternatives((prev) => prev && { ...prev, blocked: true });
        else setCompared(null);
        setApplyFeedback(COPY["toast.applyNotApplicable"]);
        return null;
      case "unauthenticated":
        closeConfirm();
        return null;
      case "failed":
        onFailed();
        return null;
    }
  }

  /** 확인창의 '적용하기'(#68, C12a-9-2). */
  async function confirmApply() {
    if (confirming === null || applying) return;
    setApplying(true);
    setConfirmError(null);
    const outcome = await applyComparison(
      { request: (method, path, body) => api.request(method, path, body) },
      { expectedRevision: confirming.revision, changes: confirming.changes, path: confirming.path },
    );
    setApplying(false);
    settleApply(outcome, confirming, () => setConfirmError(COPY["toast.applyFailed"]));
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

  // 대안은 계획이 바뀔 때마다(적용·되돌리기·입력 수정) 다시 받는다. 기한 카드에 적은 기간도 함께 보낸다(A-1-3)
  useEffect(() => {
    if (revision === null) return;
    let active = true;
    const fetchedAt = revision;
    requestAlternatives({ request: (method, path, body) => api.request(method, path, body) }).then((result) => {
      if (!active) return;
      if (result.ok) {
        setAlternativesFailed(false);
        setAlternatives({
          revision: fetchedAt,
          hasAlternatives: result.response.hasAlternatives,
          values: toAlternativeValues(result.response),
          blocked: false,
        });
        return;
      }
      if (result.kind === "noPlan") {
        planStore.acceptNoPlan(fetchedAt);
      } else if (result.kind === "failed") {
        setAlternatives(null);
        setAlternativesFailed(true);
      }
    });
    return () => {
      active = false;
    };
  }, [revision]);

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
  // 영역 그래프(#315)는 서버 projection이 있을 때만 그린다. 고른 시나리오가 응답에 없으면 기준 → 응답의 첫 시나리오 순으로 고른다
  const projection = result.projection ?? {};
  const projectionScenarios = result.scenarios.map((s) => s.scenario).filter((code) => (projection[code] ?? []).length > 0);
  const chartScenario =
    (pickedScenario && projectionScenarios.includes(pickedScenario) ? pickedScenario : null) ??
    (baselineResultScenario && projectionScenarios.includes(baselineResultScenario) ? baselineResultScenario : null) ??
    projectionScenarios[0] ??
    null;
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
    <>
      {/* 확인창이 떠 있는 동안 배경(비교 영역·대화 패널)을 막는다. 열 때의 값과 보내는 값이 달라지지 않게 한다 */}
      <div data-dialog-background className="contents" inert={confirming !== null}>
        <ResultLayout bar={<PlanConditionBar view={data} onFeedback={setApplyFeedback} />}>
          <div className="mx-auto flex max-w-5xl flex-col gap-6 px-6 py-8">
            {/* 이미 목표를 넘어도 결과를 계산해 보여 준다(#370). 안내를 맨 위에 두고 아래에 평소 결과를 잇는다 */}
            {isExceeded && (
              <section
                aria-label="목표 초과 안내"
                className="rounded-xl border border-primary bg-primary-soft p-6 text-sm text-primary-strong"
              >
                <p className="font-semibold">{exceededMessage}</p>
              </section>
            )}
            {result.scenarios.length > 0 && (
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
                        baseline.isAchieved ? "bg-success-soft text-success" : "bg-danger-soft text-danger"
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
                  productName={display["assumptions.etfProductName"]}
                  selected={chartScenario ?? undefined}
                  onSelect={chartScenario ? setPickedScenario : undefined}
                />
                {/* ETF 원금 미보장 문구와 운용사 공시(#374, D-104). ETF 0%면 종목 코드가 없어 그리지 않는다 */}
                {/* <EtfRiskNotice etfProductCode={result.assumptions.etfProductCode} /> */}
                {chartScenario && <ProjectionChart data={projection} scenario={chartScenario} startMonth={result.calcStartMonth} />}
                {/* 대안 영역(#67). 기준 시나리오가 미달일 때만 보인다(FR-ALT-01) */}
                <AlternativeSection
                  isAchieved={(baseline?.isAchieved ?? false) || alternatives?.hasAlternatives === false}
                  values={alternatives?.values}
                  onCompare={prefillComparison}
                  onApply={openAlternativeConfirm}
                  applyEnabled={alternatives !== null && !alternatives.blocked && alternatives.revision === revision}
                  loadFailed={alternativesFailed}
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
              </>
            )}

            {/* 면책(#59)은 목표 초과 화면에도 있어야 해서 분기 밖, 본문(스크롤 영역) 안에 둔다 */}
            <Disclaimer />

            {/* 적용·되돌리기 안내. 되돌린 계획이 목표 초과여도 보이게 분기 밖에 둔다 */}
            {applyFeedback && (
              <p role="status" className="text-sm text-text-muted">
                {applyFeedback}
              </p>
            )}
          </div>
        </ResultLayout>
      </div>
      {confirming !== null && (
        <ConfirmApplyDialog
          comparison={confirming.comparison}
          applying={applying}
          error={confirmError}
          onCancel={closeConfirm}
          onConfirm={() => void confirmApply()}
        />
      )}
    </>
  );
}
