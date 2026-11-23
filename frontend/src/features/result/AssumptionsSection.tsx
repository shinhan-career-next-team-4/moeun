import { COPY, type CopyMap } from "../../shared/copy/copy";
import { taxNoteFor } from "../../shared/copy/taxNote";
import type { Assumptions } from "./types";

interface AssumptionsSectionProps {
  assumptions: Assumptions;
  display: Record<string, string>;
}

/** 가정 보기(FR-RES-06, C12a-7). */
export function AssumptionsSection({ assumptions, display }: AssumptionsSectionProps) {
  const copyMap: CopyMap = COPY;
  const methodText = assumptions.scenarioMethod ? copyMap[`scenarioMethod.${assumptions.scenarioMethod}`] : null;

  return (
    <section aria-label="계산 가정" className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5 text-sm">
      <h2 className="text-base font-semibold text-text">계산에 사용한 가정</h2>
      <dl className="grid gap-2 text-text-muted sm:grid-cols-2">
        <div>
          <dt className="font-medium text-text">세율</dt>
          <dd>{display["assumptions.taxRatePct"]}</dd>
        </div>
        <div>
          <dt className="font-medium text-text">적금 금리</dt>
          <dd>{display["conditions.savingsRateInputPct"]}</dd>
        </div>
        {display["assumptions.savingsRateBaseMonth"] && (
          <div>
            <dt className="font-medium text-text">평균 금리 기준 월</dt>
            <dd>{display["assumptions.savingsRateBaseMonth"]}</dd>
          </div>
        )}
        {display["assumptions.sp500BaseDate"] && (
          <div>
            <dt className="font-medium text-text">S&P500 기준일</dt>
            <dd>{display["assumptions.sp500BaseDate"]}</dd>
          </div>
        )}
      </dl>
      <p className="text-xs leading-relaxed text-text-muted">{taxNoteFor(assumptions.etfGainTaxRatePct)}</p>
      {methodText && <p className="text-xs text-text-muted">{methodText}</p>}
      {assumptions.excludedItems.length > 0 && (
        <ul className="list-disc space-y-1 pl-5 text-xs text-text-muted">
          {assumptions.excludedItems.map((code) => (
            <li key={code}>{copyMap[`excluded.${code}`] ?? code}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
