import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import type { ComparisonConditionKey, ComparisonSide, ComparisonView } from "./types";

const BASE_CONDITION_KEYS: readonly ComparisonConditionKey[] = [
  "monthlySavingAmount",
  "targetPeriodMonths",
  "savingsRatioPct",
  "savingsRateInputPct",
];

const APPLY_LABEL = "이 조건으로 적용";

function conditionKeysFor(changedKeys: readonly ComparisonConditionKey[]): ComparisonConditionKey[] {
  return changedKeys.includes("goalAmount") ? ["goalAmount", ...BASE_CONDITION_KEYS] : [...BASE_CONDITION_KEYS];
}

function goalDisplayFor(display: Record<string, string>, sideKey: "current" | "changed"): string {
  return display[`conditions.${sideKey}.goalAmount`] ?? display["goalAmount"] ?? "";
}

function SidePanel({
  sideKey,
  label,
  side,
  baselineScenario,
  display,
}: {
  sideKey: "current" | "changed";
  label: string;
  side: ComparisonSide;
  baselineScenario: "GOOD" | "AVG" | "BAD";
  display: Record<string, string>;
}) {
  if (side.status === "ALREADY_EXCEEDED") {
    const message = COPY["chat.cardAlreadyExceeded"]
      .replace("{heldAssets}", display[`conditions.${sideKey}.heldAssets`] ?? "")
      .replace("{goalAmount}", goalDisplayFor(display, sideKey));
    return (
      <div data-testid={`comparison-${sideKey}-exceeded`} className="rounded-lg border border-primary bg-primary-soft p-4">
        <p className="text-xs font-semibold text-primary-strong">{label}</p>
        <p className="mt-1 text-sm text-primary-strong">{message}</p>
      </div>
    );
  }

  const scenario = side.baselineScenario ?? baselineScenario;
  const asset = display[`${sideKey}.${scenario}.afterTaxAsset`] ?? "";
  const gap = display[`${sideKey}.${scenario}.gapToGoal`] ?? "";
  const reachMonth = display[`${sideKey}.${scenario}.goalReachMonth`];

  return (
    <div data-testid={`comparison-${sideKey}-calculated`} className="rounded-lg border border-border bg-surface p-4">
      <p className="text-xs font-semibold text-text-muted">
        {label} · {COPY[`scenario.${scenario}`]}
      </p>
      <p className="mt-1 text-lg font-bold text-text">{asset}</p>
      <p className="mt-0.5 text-sm text-text-muted">{gap}</p>
      {reachMonth && <p className="mt-0.5 text-xs text-text-muted">{reachMonth} 달성</p>}
    </div>
  );
}

/** F-4 대화 비교 카드(D-090·D-092). 모든 금액·기간·비율은 display 문자열만 그대로 표시한다. */
export function ComparisonCard({
  comparison,
  currentGoalName = null,
  onApply,
  applying = false,
}: {
  comparison: ComparisonView;
  /** 지금 계획의 목표 이름. 목표 이름을 바꾼 비교(#345)의 현재 칸이다. */
  currentGoalName?: string | null;
  onApply?: (messageId: number) => void;
  applying?: boolean;
}) {
  const { changedKeys, current, changed, diff, display, applicable, messageId } = comparison;
  const goalChanged = changedKeys.includes("goalAmount");
  const changedGoalName = changedKeys.includes("goalCustomName") ? (comparison.goalCustomName ?? "") : null;
  // 이름은 계산에 쓰지 않아 이름만 바꾸면 양쪽 숫자가 같다(#345)
  const nameOnly = changedGoalName !== null && changedKeys.every((key) => key === "goalCustomName");
  const conditionKeys = conditionKeysFor(changedKeys);
  const bothCalculated = current.status === "CALCULATED" && changed.status === "CALCULATED" && diff.byScenario.length > 0;
  const deltaText = bothCalculated ? display[`diff.${diff.baselineScenario}.afterTaxAssetDelta`] : undefined;

  const goalChangedNote = goalChanged
    ? COPY["chat.goalAmountChangedNote"]
        .replace("{currentGoal}", display["conditions.current.goalAmount"] ?? "")
        .replace("{changedGoal}", display["conditions.changed.goalAmount"] ?? "")
    : null;

  return (
    <section data-testid="comparison-card" aria-label="조건 비교" className="rounded-xl border border-border bg-surface p-4 shadow-sm">
      <dl data-testid="comparison-conditions" className="grid grid-cols-3 gap-x-3 gap-y-1.5 text-xs">
        <dt className="font-semibold text-text-muted">조건</dt>
        <dd className="font-semibold text-text-muted">현재 조건</dd>
        <dd className="font-semibold text-text-muted">바꾼 조건</dd>
        {changedGoalName !== null && (
          <div data-testid="comparison-condition-goalCustomName" className="contents">
            <dt className="text-text-muted">{COPY["condition.goalCustomName"]}</dt>
            <dd className="min-w-0 text-text wrap-anywhere">{currentGoalName ?? ""}</dd>
            <dd className="min-w-0 font-medium text-text wrap-anywhere">{changedGoalName}</dd>
          </div>
        )}
        {conditionKeys.map((key) => (
          <div key={key} data-testid={`comparison-condition-${key}`} className="contents">
            <dt className="text-text-muted">{COPY[`condition.${key}`]}</dt>
            <dd className="text-text">{display[`conditions.current.${key}`] ?? ""}</dd>
            <dd className="font-medium text-text">{display[`conditions.changed.${key}`] ?? ""}</dd>
          </div>
        ))}
      </dl>

      <div className="mt-4 grid grid-cols-2 gap-3">
        <SidePanel
          sideKey="current"
          label="현재 조건"
          side={current}
          baselineScenario={diff.baselineScenario}
          display={display}
        />
        <SidePanel
          sideKey="changed"
          label="바꾼 조건"
          side={changed}
          baselineScenario={diff.baselineScenario}
          display={display}
        />
      </div>

      {deltaText && (
        <p data-testid="comparison-diff-delta" className="mt-3 text-sm font-medium text-text">
          {deltaText}
        </p>
      )}

      {goalChangedNote !== null && (
        <p data-testid="comparison-goal-changed-note" className="mt-2 text-xs text-text-muted">
          {goalChangedNote}
        </p>
      )}

      {nameOnly && (
        <p data-testid="comparison-goal-name-note" className="mt-2 text-xs text-text-muted">
          {COPY["chat.goalNameOnlyNote"]}
        </p>
      )}

      {changed.status === "ALREADY_EXCEEDED" && (
        <p data-testid="comparison-changed-exceeded-note" className="mt-2 text-xs font-medium text-primary-strong">
          {COPY["chat.changedAlreadyExceeded"]}
        </p>
      )}

      {onApply && (
        <div className="mt-4 flex justify-end">
          <Button
            type="button"
            data-testid="comparison-apply"
            disabled={!applicable || applying}
            onClick={() => onApply(messageId)}
          >
            {APPLY_LABEL}
          </Button>
        </div>
      )}
    </section>
  );
}
