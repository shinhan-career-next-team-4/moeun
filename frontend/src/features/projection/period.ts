import { COPY } from "../../shared/copy/copy";
import { splitMonths } from "../../shared/units/units";

/** 개월 수를 "N개월" / "N년" / "N년 M개월"로 쓴다. 단위 변환만 하며 서버가 준 기간을 바꾸지 않는다. */
export function formatPeriod(totalMonths: number): string {
  const { years, months } = splitMonths(totalMonths);
  if (years === 0) return COPY["projection.periodMonths"].replace("{m}", String(months));
  if (months === 0) return COPY["projection.periodYears"].replace("{y}", String(years));
  return COPY["projection.periodYearsMonths"].replace("{y}", String(years)).replace("{m}", String(months));
}
