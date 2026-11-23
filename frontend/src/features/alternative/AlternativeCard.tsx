import { useState } from "react";
import { COPY } from "../../shared/copy/copy";
import { Button, UnitInput } from "../../shared/ui";
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
  /** 기한 카드에서 사용자가 적은 기간(개월). D-020·D-039: 기간은 사용자가 정한다 */
  onPeriodSubmit?: (months: number) => void;
  /** 기한 카드의 범위 안내(A-1-2 422). 있으면 '○개월로 계산했어요' 대신 보인다 */
  periodError?: string | null;
}

interface RowProps {
  label: string;
  value?: string;
  tone?: "normal" | "warn";
  suffix?: string;
}

function Row({ label, value, tone = "normal", suffix }: RowProps) {
  if (!value) return null;
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="shrink-0 text-xs text-text-muted">{label}</dt>
      <dd className={`text-right text-sm font-medium ${tone === "warn" ? "text-danger" : "text-text"}`}>
        {value}
        {suffix && <span className="ml-1 whitespace-nowrap">{suffix}</span>}
      </dd>
    </div>
  );
}

/** 기한 카드의 기간 입력(FR-ALT-02, D-020·D-039). 입력 전에는 안내만 있는 빈 카드다. */
function PeriodInput({ onSubmit, error }: { onSubmit: (months: number) => void; error?: string | null }) {
  const [months, setMonths] = useState("");
  const [submitted, setSubmitted] = useState<number | null>(null);
  const parsed = Number(months);
  const canSubmit = /^\d+$/.test(months) && parsed > 0;

  function submit() {
    setSubmitted(parsed);
    onSubmit(parsed);
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs leading-relaxed text-text-muted">{COPY["alt.periodPrompt"]}</p>
      <UnitInput
        id="alt-period-months"
        label={COPY["alt.periodLabel"]}
        unit="개월"
        value={months}
        onChange={setMonths}
      />
      <Button variant="secondary" className="h-9 px-3" disabled={!canSubmit} onClick={submit}>
        {COPY["alt.periodSubmit"]}
      </Button>
      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
      {!error && submitted !== null && (
        <p className="text-xs text-text-muted">
          {COPY["alt.periodSubmitted"].replace("{months}", String(submitted))}
        </p>
      )}
    </div>
  );
}

/** 대안 카드 하나(FR-ALT-02). 서버 값이 없는 줄은 그리지 않고, 하나도 없으면 안내만 보여 준다. */
export function AlternativeCard({
  alternativeKey,
  values,
  onCompare,
  onApply,
  applyEnabled = false,
  onPeriodSubmit,
  periodError,
}: AlternativeCardProps) {
  const hasValues =
    values !== undefined && Object.values(values).some((value) => typeof value === "string" && value.length > 0);

  return (
    <li className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
      <h3 className="text-sm font-bold text-text">{COPY[`alt.card.${alternativeKey}`]}</h3>
      {hasValues ? (
        <dl className="flex flex-col gap-1.5">
          <Row label={COPY["alt.rowChangedCondition"]} value={values.changedCondition} />
          <Row label={COPY["alt.rowChange"]} value={values.change} />
          <Row label={COPY["alt.rowAvgAsset"]} value={values.avgAfterTaxAsset} />
          <Row label={COPY["alt.rowGapToGoal"]} value={values.gapToGoal} />
          <Row
            label={COPY["alt.rowBad"]}
            value={values.badAfterTaxAsset}
            tone="warn"
            suffix={values.volatilityWarning ? COPY["alt.volatilityWarning"] : undefined}
          />
        </dl>
      ) : (
        <p className="text-xs text-text-muted">{COPY["alt.valuePending"]}</p>
      )}
      {onPeriodSubmit && <PeriodInput onSubmit={onPeriodSubmit} error={periodError} />}
      <div className="mt-auto flex gap-2 pt-1">
        <Button
          variant="secondary"
          className="h-9 flex-1 px-2"
          disabled={!hasValues || !onCompare}
          onClick={() => values && onCompare?.(values)}
        >
          {COPY["alt.compare"]}
        </Button>
        {/* 적용은 확인창(FR-PLAN-01)을 거쳐 P-6 ALTERNATIVE로 한다(#258) */}
        <Button
          variant="secondary"
          className="h-9 flex-1 px-2"
          disabled={!hasValues || !onApply || !applyEnabled}
          onClick={() => values && onApply?.(values)}
        >
          {COPY["alt.apply"]}
        </Button>
      </div>
    </li>
  );
}
