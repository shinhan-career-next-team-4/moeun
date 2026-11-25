export type LayerKey = "principal" | "savingsInterest" | "etfGain";

export interface LayerStyle {
  key: LayerKey;
  /** 테두리 선과 범례·툴팁 점의 색. */
  color: string;
  /** 영역 채움의 투명도. */
  fillOpacity: number;
  /** 문구표 키. */
  labelKey: "projection.principal" | "projection.savingsInterest" | "projection.etfGain";
}

/**
 * 층의 순서는 아래부터 원금 → 적금 이자 → ETF 수익이다(#298).
 * 원금은 조용한 회색, 이자(초록/청록)와 수익(파랑)만 눈에 띄게 한다.
 */
export const LAYERS: readonly LayerStyle[] = [
  { key: "principal", color: "#787a91", fillOpacity: 0.28, labelKey: "projection.principal" },
  { key: "savingsInterest", color: "#0d9488", fillOpacity: 0.38, labelKey: "projection.savingsInterest" },
  { key: "etfGain", color: "#2563eb", fillOpacity: 0.38, labelKey: "projection.etfGain" },
];
