const WON_PER_MANWON = 10_000;

/** 공백·쉼표 제거, 전각 숫자·마침표를 반각으로 */
function normalize(raw: string): string {
  return raw
    .replace(/[０-９]/g, (digit) => String.fromCharCode(digit.charCodeAt(0) - 0xfee0))
    .replace(/．/g, ".")
    .replace(/[\s,]/g, "");
}

export function isBlank(raw: string): boolean {
  return normalize(raw) === "";
}

/** 만원 단위 정수 입력 → 원. 0 이상 정수만 허용 */
export function parseManwon(raw: string): { ok: true; won: number } | { ok: false } {
  const value = normalize(raw);
  if (!/^\d+$/.test(value)) {
    return { ok: false };
  }
  return { ok: true, won: Number(value) * WON_PER_MANWON };
}

/** 0 이상 정수 */
export function parseCount(raw: string): { ok: true; value: number } | { ok: false } {
  const value = normalize(raw);
  return /^\d+$/.test(value) ? { ok: true, value: Number(value) } : { ok: false };
}

/** 0 이상 소수(금리) */
export function parseDecimal(raw: string): { ok: true; value: number } | { ok: false } {
  const value = normalize(raw);
  return /^\d+(\.\d+)?$/.test(value) ? { ok: true, value: Number(value) } : { ok: false };
}

/** 소수 자릿수(금리 소수 둘째 자리 검사용). "3.250"은 2자리로 본다 */
export function decimalPlaces(raw: string): number {
  const fraction = normalize(raw).split(".")[1] ?? "";
  return fraction.replace(/0+$/, "").length;
}
