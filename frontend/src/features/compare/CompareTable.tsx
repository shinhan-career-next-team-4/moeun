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
}

const DASH = "—";

const TONE_CLASS = { good: "font-medium text-success", bad: "font-medium text-danger", none: "text-text-muted" } as const;

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
  const achievementTone = (scenario: string): Row["tone"] => {
    const change = achievement(scenario);
    if (change === "MISS_TO_ACHIEVE") return "good";
    if (change === "ACHIEVE_TO_MISS") return "bad";
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
    },
    {
      label: COPY["compare.rowBad"],
      current: view.display["current.BAD.afterTaxAsset"],
      changed: view.display["changed.BAD.afterTaxAsset"],
      change: achievementText("BAD"),
      tone: achievementTone("BAD"),
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

/** 비교 표(FR-CMP-02, 와이어프레임 03). 4열: 항목·현재 조건·바꾼 조건·변화 */
export function CompareTable({ view }: { view: ComparisonView }) {
  const copy: CopyMap = COPY;
  const rows = [...conditionRows(view, copy), ...resultRows(view)];

  return (
    <table className="w-full table-fixed border-collapse text-sm">
      <thead>
        <tr className="border-b border-border bg-table-head text-xs text-text-muted-on-soft">
          <th scope="col" className="py-2 text-left font-medium">
            {COPY["compare.colItem"]}
          </th>
          <th scope="col" className="py-2 text-right font-medium">
            {COPY["compare.colCurrent"]}
          </th>
          <th scope="col" className="py-2 text-right font-medium">
            {COPY["compare.colChanged"]}
          </th>
          <th scope="col" className="py-2 text-right font-medium">
            {COPY["compare.colChange"]}
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.label} className="border-b border-border last:border-none">
            <th scope="row" className="py-2 text-left font-medium text-text-muted">
              {row.label}
            </th>
            <td className="py-2 text-right text-text">{row.current ?? DASH}</td>
            <td className="py-2 text-right font-medium text-text">{row.changed ?? DASH}</td>
            <td className={`py-2 text-right ${TONE_CLASS[row.tone ?? "none"]}`}>{row.change ?? DASH}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
