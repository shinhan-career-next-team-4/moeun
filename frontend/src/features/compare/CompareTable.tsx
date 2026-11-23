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
}

const DASH = "—";

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
    },
    {
      label: COPY["compare.rowBad"],
      current: view.display["current.BAD.afterTaxAsset"],
      changed: view.display["changed.BAD.afterTaxAsset"],
      change: achievementText("BAD"),
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

/**
 * 비교 표가 어느 시점에서 보는 표인지.
 * - `preview`: 적용하기 전. 4열은 항목·현재 조건·바꾼 조건·변화(FR-CMP-02, 와이어프레임 03)
 * - `applied`: 적용한 뒤(#156). 내 계획이 이미 바뀌었으므로 항목·바뀐 조건·이전 조건·변화 순서로 보여 준다
 */
export type CompareTableVariant = "preview" | "applied";

/** 값 열 두 개의 순서와 머리글. 변화 열은 두 경우 모두 바뀐 값 - 이전 값이다. */
const VALUE_COLUMNS: Record<
  CompareTableVariant,
  { first: "current" | "changed"; second: "current" | "changed"; firstLabel: string; secondLabel: string }
> = {
  preview: { first: "current", second: "changed", firstLabel: "compare.colCurrent", secondLabel: "compare.colChanged" },
  applied: { first: "changed", second: "current", firstLabel: "compare.colNow", secondLabel: "compare.colPrevious" },
};

/** 비교 표(FR-CMP-02, 와이어프레임 03). */
export function CompareTable({ view, variant = "preview" }: { view: ComparisonView; variant?: CompareTableVariant }) {
  const copy: CopyMap = COPY;
  const rows = [...conditionRows(view, copy), ...resultRows(view)];
  const columns = VALUE_COLUMNS[variant];
  // 새로 바뀐 값을 굵게 보인다(적용 전엔 바꾼 조건, 적용 뒤엔 바뀐 조건)
  const emphasized = "changed";

  return (
    <table className="w-full table-fixed border-collapse text-sm">
      <thead>
        <tr className="border-b border-border text-xs text-text-muted">
          <th scope="col" className="py-2 text-left font-medium">
            {COPY["compare.colItem"]}
          </th>
          <th scope="col" className="py-2 text-right font-medium">
            {copy[columns.firstLabel]}
          </th>
          <th scope="col" className="py-2 text-right font-medium">
            {copy[columns.secondLabel]}
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
            {[columns.first, columns.second].map((side) => (
              <td
                key={side}
                className={`py-2 text-right ${side === emphasized ? "font-medium text-text" : "text-text"}`}
              >
                {row[side] ?? DASH}
              </td>
            ))}
            <td className="py-2 text-right text-text-muted">{row.change ?? DASH}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
