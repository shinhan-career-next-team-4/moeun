import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import type { AlternativeKey, AlternativeValues } from "./types";

interface AlternativeCardProps {
  alternativeKey: AlternativeKey;
  values?: AlternativeValues;
  /** '비교하기'. 이 대안의 조건을 비교 영역에 채운다(프론트 상태만, 저장 없음) */
  onCompare?: (values: AlternativeValues) => void;
  /** '내 계획으로 적용'. 확인창을 거쳐 P-6 ALTERNATIVE로 적용한다(#258) */
  onApply?: (values: AlternativeValues) => void;
  /** 부모가 적용을 허용하는지(대안을 받은 뒤 내 계획이 그대로) */
  applyEnabled?: boolean;
}

interface RowProps {
  label: string;
  value?: string;
  tone?: "normal" | "warn" | "good";
  suffix?: string;
}

function Row({ label, value, tone = "normal", suffix }: RowProps) {
  if (!value) return null;
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="shrink-0 text-xs text-text-muted">{label}</dt>
      <dd className={`text-right text-sm font-medium ${tone === "warn" ? "text-danger" : tone === "good" ? "text-success" : "text-text"}`}>
        {value}
        {suffix && <span className="ml-1 whitespace-nowrap">{suffix}</span>}
      </dd>
    </div>
  );
}

/** 달성 불가 안내. 월 저축액은 서버가 준 상한 금액을 넣고, 없으면 상한 없이 안내한다. */
function unreachableMessage(key: AlternativeKey, limit?: string): string {
  if (key === "monthlySavingAmount") {
    return limit
      ? COPY["alt.unreachable.monthlySavingAmount"].replace("{limit}", limit)
      : COPY["alt.unreachable.monthlySavingAmountNoLimit"];
  }
  return COPY[`alt.unreachable.${key}`];
}

/** 대안 카드 하나(FR-ALT-02). 서버 값이 없는 줄은 그리지 않고, 하나도 없으면 안내만 보여 준다. 달성이 어려우면 그 안내만 보인다(#343). */
export function AlternativeCard({
  alternativeKey,
  values,
  onCompare,
  onApply,
  applyEnabled = false,
}: AlternativeCardProps) {
  const unreachable = values?.unreachable === true;
  const hasValues =
    !unreachable &&
    values !== undefined &&
    Object.values(values).some((value) => typeof value === "string" && value.length > 0);

  return (
    <li className="flex flex-col gap-3 rounded-xl border border-border bg-surface-subtle p-4">
      <h3 className="text-sm font-bold text-text">{COPY[`alt.card.${alternativeKey}`]}</h3>
      {hasValues ? (
        <dl className="flex flex-col gap-1.5">
          <Row label={COPY["alt.rowChangedCondition"]} value={values.changedCondition} />
          <Row label={COPY["alt.rowChange"]} value={values.change} />
          <Row label={COPY["alt.rowAvgAsset"]} value={values.avgAfterTaxAsset} tone={values.achieved ? "good" : "normal"} />
          <Row label={COPY["alt.rowGapToGoal"]} value={values.gapToGoal} tone={values.achieved ? "good" : "normal"} />
          {/* '나빴던 때' 줄은 뺐다(#423). 변동 경고(W-10)는 서버가 알려 준 카드에만 한 줄로 남긴다 */}
          {values.volatilityWarning && <p className="pt-1 text-right text-xs font-medium text-danger">{COPY["alt.volatilityWarning"]}</p>}
        </dl>
      ) : unreachable ? (
        <p className="text-sm leading-relaxed text-danger">{unreachableMessage(alternativeKey, values?.unreachableLimit)}</p>
      ) : (
        <p className="text-xs text-text-muted">{COPY["alt.valuePending"]}</p>
      )}
      <div className="mt-auto flex flex-wrap gap-2 pt-1">
        <Button
          variant="secondary"
          className="h-9 min-w-fit flex-1 whitespace-nowrap px-2"
          disabled={!hasValues || !onCompare}
          onClick={() => values && onCompare?.(values)}
        >
          {COPY["alt.compare"]}
        </Button>
        {/* 적용은 확인창(FR-PLAN-01)을 거쳐 P-6 ALTERNATIVE로 한다(#258) */}
        <Button
          variant="secondary"
          className="h-9 min-w-fit flex-1 whitespace-nowrap px-2"
          disabled={!hasValues || !onApply || !applyEnabled}
          onClick={() => values && onApply?.(values)}
        >
          {COPY["alt.apply"]}
        </Button>
      </div>
    </li>
  );
}
