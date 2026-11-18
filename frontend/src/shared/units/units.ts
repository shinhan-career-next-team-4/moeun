// C12a-5-6 입력 변환 규칙과 C12a-5-1 목표 표시 helper. 부동소수 곱셈을 쓰지 않는다.
import { COPY, type CopyMap } from "../copy/copy";

export interface ServerFieldError {
  field: string;
  reason: string;
  min?: number;
  max?: number;
  unit?: string;
  scale?: number;
}

/** 공백·쉼표 제거, 전각 숫자·마침표를 반각으로 */
export function normalizeNumberInput(raw: string): string {
  return raw
    .replace(/[０-９]/g, (digit) => String.fromCharCode(digit.charCodeAt(0) - 0xfee0))
    .replace(/．/g, ".")
    .replace(/[\s,]/g, "");
}

export function isBlank(raw: string): boolean {
  return normalizeNumberInput(raw) === "";
}

/** 만원 문자열(소수 4자리까지) → 원 정수. 소수점을 오른쪽으로 네 칸 옮기는 문자열 연산. */
export function manwonToWon(raw: string): { ok: true; won: number } | { ok: false } {
  const value = normalizeNumberInput(raw);
  const match = /^(\d+)(?:\.(\d{1,4}))?$/.exec(value);
  if (!match) return { ok: false };
  const intPart = match[1];
  const fracPart = (match[2] ?? "").padEnd(4, "0");
  const won = Number(`${intPart}${fracPart}`);
  if (!Number.isSafeInteger(won)) return { ok: false };
  return { ok: true, won };
}

/** 원 정수 → 만원 문자열. 소수점을 왼쪽으로 네 칸 옮기고 끝의 0과 소수점을 지운다. */
export function wonToManwon(won: number): string {
  if (!Number.isFinite(won) || won < 0) return "";
  const digits = Math.trunc(won).toString();
  if (digits === "0") return "0";
  const padded = digits.padStart(5, "0");
  const splitAt = padded.length - 4;
  const intPart = padded.slice(0, splitAt).replace(/^0+/, "") || "0";
  const fracPart = padded.slice(splitAt).replace(/0+$/, "");
  return fracPart ? `${intPart}.${fracPart}` : intPart;
}

/** 개월 수 분해/합산 */
export function toMonths(years: number, months: number): number {
  return years * 12 + months;
}

export function splitMonths(totalMonths: number): { years: number; months: number } {
  return { years: Math.floor(totalMonths / 12), months: totalMonths % 12 };
}

export function parseNonNegativeInt(raw: string): { ok: true; value: number } | { ok: false } {
  const value = normalizeNumberInput(raw);
  if (!/^\d+$/.test(value)) return { ok: false };
  return { ok: true, value: Number(value) };
}

export function parseDecimalInput(raw: string): { ok: true; value: number } | { ok: false } {
  const value = normalizeNumberInput(raw);
  if (!/^\d+(\.\d+)?$/.test(value)) return { ok: false };
  return { ok: true, value: Number(value) };
}

function addCommas(numericText: string): string {
  const [intPart, fracPart] = numericText.split(".");
  const withCommas = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return fracPart ? `${withCommas}.${fracPart}` : withCommas;
}

/** 422 min·max·unit을 입력칸 단위의 안내 문구로 바꾼다(C12a-5-6 (f)). */
export function formatUnitValue(value: number, unit: string | undefined, copy: CopyMap = COPY): string {
  if (unit === "KRW") {
    if (value === 0) return (copy["unit.won"] ?? "{v}원").replace("{v}", "0");
    return (copy["unit.manwon"] ?? "{v}만원").replace("{v}", addCommas(wonToManwon(value)));
  }
  if (unit === "MONTH") {
    const { years, months } = splitMonths(value);
    return (copy["unit.month"] ?? "{y}년 {m}개월").replace("{y}", String(years)).replace("{m}", String(months));
  }
  if (unit === "PCT") {
    return (copy["unit.pct"] ?? "연 {v}%").replace("{v}", String(value));
  }
  if (unit === "CHAR") {
    return (copy["unit.char"] ?? "{n}자").replace("{n}", String(value));
  }
  return String(value);
}

export function formatFieldError(error: ServerFieldError, copy: CopyMap = COPY): string {
  if (error.reason === "OUT_OF_RANGE" && error.min !== undefined && error.max !== undefined) {
    const template = copy["validation.OUT_OF_RANGE"] ?? "{min}~{max} 사이로 입력해 주세요";
    return template
      .replace("{min}", formatUnitValue(error.min, error.unit, copy))
      .replace("{max}", formatUnitValue(error.max, error.unit, copy));
  }
  if (error.reason === "TOO_MANY_DECIMALS") {
    return (copy["validation.TOO_MANY_DECIMALS"] ?? "소수 {scale}자리까지 입력할 수 있어요").replace(
      "{scale}",
      String(error.scale ?? 2),
    );
  }
  return copy[`validation.${error.reason}`] ?? copy["validation.REQUIRED"];
}

/** 문구표에 없는 goal.<CODE>를 만나면 코드 문자열을 그대로 표시한다(C12a-5-1). */
export function goalLabel(code: string, copy: CopyMap = COPY): string {
  return copy[`goal.${code}`] ?? code;
}
