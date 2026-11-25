import { COPY, type CopyMap } from "../../shared/copy/copy";
import { conditionValues } from "./conditionValues";
import type { ComparedKey, ComparisonView } from "./types";

const CONDITION_LABEL_KEY: Record<ComparedKey, string> = {
  goalAmount: "condition.goalAmount",
  monthlySavingAmount: "condition.monthlySavingAmount",
  savingsRatioPct: "condition.savingsRatioPct",
  savingsRateInputPct: "condition.savingsRateInputPct",
  targetPeriodMonths: "condition.targetPeriodMonths",
  etfProductCode: "condition.etfProductCode",
};

/** 표의 한 줄. 값이 없는 칸은 '—'로 둔다(프론트가 값을 만들지 않는다). */
interface Row {
  label: string;
  current?: string;
  changed?: string;
  change?: string;
  /** 변화 문구의 색. 달성으로 바뀌면 good, 미달로 바뀌면 bad. 서버가 준 achievementChange 값만 본다 */
  tone?: "good" | "bad";
  /** 현재·바꾼 칸의 색. 달성이면 good, 미달이면 bad. 서버가 준 achievementChange로만 정한다 */
  currentTone?: "good" | "bad";
  changedTone?: "good" | "bad";
}

const DASH = "—";

const TONE_CLASS = { good: "font-medium text-success", bad: "font-medium text-danger", none: "text-text" } as const;

function conditionRows(view: ComparisonView, copy: CopyMap): Row[] {
  return [...view.changedKeys].sort().map((key) => ({
    label: copy[CONDITION_LABEL_KEY[key]] ?? key,
    ...conditionValues(view, key),
    // 조건 자체의 변화량(`+30만 원`) display 키는 계약에 없다(W-15과 같은 이유)
    change: undefined,
  }));
}

function resultRows(view: ComparisonView): Row[] {
  const baseline = view.diff.baselineScenario;
  const achievement = (scenario: string) =>
    view.diff.byScenario.find((item) => item.scenario === scenario)?.achievementChange;
  const achievementText = (scenario: string) => {
    const change = achievement(scenario);
    return change ? COPY[`achievementChange.${change}`] : undefined;
  };
  const sideTones = (scenario: string): Pick<Row, "currentTone" | "changedTone"> => {
    const change = achievement(scenario);
    switch (change) {
      case "STAYS_ACHIEVED":
        return { currentTone: "good", changedTone: "good" };
      case "STAYS_MISSED":
        return { currentTone: "bad", changedTone: "bad" };
      case "MISS_TO_ACHIEVE":
        return { currentTone: "bad", changedTone: "good" };
      case "ACHIEVE_TO_MISS":
        return { currentTone: "good", changedTone: "bad" };
      default:
        return {};
    }
  };
  const achievementTone = (scenario: string): Row["tone"] => {
    const change = achievement(scenario);
    if (change === "MISS_TO_ACHIEVE" || change === "STAYS_ACHIEVED") return "good";
    if (change === "ACHIEVE_TO_MISS" || change === "STAYS_MISSED") return "bad";
    return undefined;
  };

  return [
    {
      label: COPY["compare.rowAvgAsset"],
      current: view.display[`current.${baseline}.afterTaxAsset`],
      changed: view.display[`changed.${baseline}.afterTaxAsset`],
      change: view.display[`diff.${baseline}.afterTaxAssetDelta`],
    },
    {
      label: COPY["compare.rowGapToGoal"],
      current: view.display[`current.${baseline}.gapToGoal`],
      changed: view.display[`changed.${baseline}.gapToGoal`],
      change: achievementText(baseline),
      tone: achievementTone(baseline),
      ...sideTones(baseline),
    },
    {
      label: COPY["compare.rowBad"],
      current: view.display["current.BAD.afterTaxAsset"],
      changed: view.display["changed.BAD.afterTaxAsset"],
      change: achievementText("BAD"),
      tone: achievementTone("BAD"),
      ...sideTones("BAD"),
    },
    {
      label: COPY["compare.rowPrincipal"],
      current: view.display[`current.${baseline}.principal`],
      changed: view.display[`changed.${baseline}.principal`],
      change: view.display[`diff.${baseline}.principalDelta`],
    },
    {
      label: COPY["compare.rowGoalReachMonth"],
      current: view.display[`current.${baseline}.goalReachMonth`] ?? COPY["result.notReachedInPeriod"],
      changed: view.display[`changed.${baseline}.goalReachMonth`] ?? COPY["result.notReachedInPeriod"],
      // 몇 개월 빨라졌는지는 계약에 두지 않았다(internal-calc C2-5, W-15)
      change: undefined,
    },
  ];
}

const SIDE_TONE_CLASS = { good: "text-success", bad: "text-danger", none: "text-text" } as const;

/** 비교 표(FR-CMP-02, 와이어프레임 03). 4열: 항목·현재 조건·바꾼 조건·변화. 둥근 테두리 안에 왼쪽 정렬로 그린다 */
export function CompareTable({ view }: { view: ComparisonView }) {
  const copy: CopyMap = COPY;
  const rows = [...conditionRows(view, copy), ...resultRows(view)];

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface">
      <table className="w-full table-fixed border-collapse text-sm">
        <thead>
          <tr className="bg-table-head text-sm text-text-muted-on-soft">
            <th scope="col" className="px-4 py-3 text-left font-medium">
              {COPY["compare.colItem"]}
            </th>
            <th scope="col" className="px-4 py-3 text-left font-medium">
              {COPY["compare.colCurrent"]}
            </th>
            <th scope="col" className="px-4 py-3 text-left font-medium">
              {COPY["compare.colChanged"]}
            </th>
            <th scope="col" className="px-4 py-3 text-left font-medium">
              {COPY["compare.colChange"]}
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label} className="border-t border-border">
              <th scope="row" className="px-4 py-4 text-left font-semibold text-text">
                {row.label}
              </th>
              <td className={`px-4 py-4 text-left ${SIDE_TONE_CLASS[row.currentTone ?? "none"]}`}>
                {row.current ?? DASH}
              </td>
              <td className={`px-4 py-4 text-left font-semibold ${SIDE_TONE_CLASS[row.changedTone ?? "none"]}`}>
                {row.changed ?? DASH}
              </td>
              <td className={`px-4 py-4 text-left ${TONE_CLASS[row.tone ?? "none"]}`}>{row.change ?? DASH}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
