import { COPY } from "./copy";

/**
 * 계획에 맞는 세금 안내 문장(D-067, #233). ETF 매매차익 세율이 0(국내 주식형 비과세)이면 비과세 안내,
 * 그 밖(과세 ETF, ETF 0%, 값 없음)은 일반 안내다. ai-server가 고르던 기준과 같다.
 */
export function taxNoteFor(etfGainTaxRatePct: number | null | undefined): string {
  return etfGainTaxRatePct === 0 ? COPY["tax.noteEtfTaxFree"] : COPY["tax.note"];
}
