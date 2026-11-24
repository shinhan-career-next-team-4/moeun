import { COPY } from "../../shared/copy/copy";
import { splitMonths } from "../../shared/units/units";

/** 개월 수를 "N개월" / "N년" / "N년 M개월"로 쓴다. 단위 변환만 하며 서버가 준 기간을 바꾸지 않는다. */
export function formatPeriod(totalMonths: number): string {
  const { years, months } = splitMonths(totalMonths);
  if (years === 0) return COPY["projection.periodMonths"].replace("{m}", String(months));
  if (months === 0) return COPY["projection.periodYears"].replace("{y}", String(years));
  return COPY["projection.periodYearsMonths"].replace("{y}", String(years)).replace("{m}", String(months));
}

/**
 * 계산 시작월(`"YYYY-MM"`)에서 `monthsAfter`개월 뒤를 `"YYYY.MM"`으로 쓴다(가로축 눈금 라벨). 달력 이동만 하며 서버가 준 시작월을 바꾸지 않는다.
 * 시작월 형식이 아니면 null이다.
 */
export function formatMonthLabel(startMonth: string, monthsAfter: number): string | null {
  const match = /^(\d{4})-(\d{2})$/.exec(startMonth);
  if (match === null) return null;
  const index = Number(match[1]) * 12 + (Number(match[2]) - 1) + monthsAfter;
  const year = Math.floor(index / 12);
  const month = (index % 12) + 1;
  return `${year}.${String(month).padStart(2, "0")}`;
}
