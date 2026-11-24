import { COPY } from "../../shared/copy/copy";
import { ETF_DISCLOSURES } from "./etfDisclosures";

interface EtfRiskNoticeProps {
  /** 계산에 쓴 ETF 종목 코드(`assumptions.etfProductCode`). ETF 0%면 null이라 블록을 그리지 않는다(#150). */
  etfProductCode: string | null | undefined;
}

/**
 * ETF 원금 미보장 고정 문구와 운용사 공시 링크(#374, D-104, FR-AI-03).
 * LLM 설명에 맡기지 않고 화면에 고정해 늘 같은 문장과 출처가 보이게 한다. 공시 목록에 없는 종목이면 문구만 보인다.
 */
export function EtfRiskNotice({ etfProductCode }: EtfRiskNoticeProps) {
  if (!etfProductCode) return null;
  const disclosures = ETF_DISCLOSURES[etfProductCode] ?? [];

  return (
    <div
      role="note"
      aria-label={COPY["result.etfDisclosureLabel"]}
      className="flex flex-col gap-2 rounded-xl border border-border bg-surface-muted p-4 text-xs text-text-muted"
    >
      <p className="text-sm font-medium text-text">{COPY["result.etfPrincipalRisk"]}</p>
      {disclosures.length > 0 && (
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <span className="font-semibold">{COPY["result.etfDisclosureLabel"]}</span>
          <ul className="flex flex-wrap gap-x-3 gap-y-1">
            {disclosures.map((doc) => (
              <li key={doc.sourceUrl}>
                <a href={doc.sourceUrl} target="_blank" rel="noopener noreferrer" className="underline hover:text-text">
                  {doc.title}
                </a>{" "}
                <span>{doc.orgName}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
