import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { PRINCIPAL_LINE_COLOR, SCENARIO_SERIES } from "./ResultChart";
import type { MonthlyDetail } from "./types";

interface MonthDetailPanelProps {
  detail: MonthlyDetail;
  onClose(): void;
}

interface RowProps {
  color: string;
  label: string;
  value: string;
}

function Row({ color, label, value }: RowProps) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="flex items-center gap-1.5 text-xs text-text-muted">
        <span aria-hidden className="inline-block size-2 rounded-full" style={{ backgroundColor: color }} />
        {label}
      </dt>
      <dd className="text-right text-sm font-medium text-text">{value}</dd>
    </div>
  );
}

/**
 * 그래프에서 고른 달의 값(#292, 계약 C12a-6-4). 월 라벨과 금액은 모두 서버가 서식한 문자열이라 그대로 보인다(NFR-INT-01).
 * 시나리오는 좋았던 때·평균·나빴던 때 순서로, 응답에 있는 것만 보인다.
 */
export function MonthDetailPanel({ detail, onClose }: MonthDetailPanelProps) {
  const scenarioRows = SCENARIO_SERIES.flatMap(({ code, color, label }) => {
    const value = detail.afterTaxAsset[code];
    return value === undefined ? [] : [{ code, color, label, value }];
  });

  return (
    <section
      aria-label={COPY["result.monthDetailLabel"]}
      className="rounded-xl border border-border bg-surface p-5 shadow-sm"
    >
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-text">{detail.label}</h3>
        <Button variant="ghost" onClick={onClose}>
          {COPY["result.monthDetailClose"]}
        </Button>
      </div>
      <dl className="mt-3 flex flex-col gap-2">
        {scenarioRows.map((row) => (
          <Row key={row.code} color={row.color} label={row.label} value={row.value} />
        ))}
        <Row color={PRINCIPAL_LINE_COLOR} label={COPY["result.chartLegendPrincipal"]} value={detail.principal} />
      </dl>
    </section>
  );
}
