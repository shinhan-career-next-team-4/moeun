// CP-04 표시 형식. 계산하지 않고 받은 숫자의 모양만 바꾼다. 입력·금액은 원 단위.

const WON_PER_MANWON = 10_000;
const MINUS = "−";

function withComma(value: number): string {
  return value.toLocaleString("ko-KR");
}

/** 결과 금액: 천원 단위 반올림 → 만원 단위 반올림 → "약 N만원" */
export function formatMoney(won: number): string {
  const thousandRounded = Math.round(won / 1_000) * 1_000;
  return `약 ${withComma(Math.round(thousandRounded / WON_PER_MANWON))}만원`;
}

/** 입력값·조건 바: 정확한 만원 값 */
export function formatManwon(won: number): string {
  return `${withComma(Math.round(won / WON_PER_MANWON))}만원`;
}

/** 목표 대비: 부호 + 달성/미달. 0은 달성(TBD-20) */
export function formatGoalDiff(won: number): string {
  const manwon = Math.round(won / WON_PER_MANWON);
  if (manwon === 0) {
    return "0만원 달성";
  }
  const sign = manwon > 0 ? "+" : MINUS;
  const label = manwon > 0 ? "달성" : "미달";
  return `${sign}${withComma(Math.abs(manwon))}만원 ${label}`;
}

export function formatPeriod(months: number | null): string {
  if (months === null) {
    return "기간 미정";
  }
  const years = Math.floor(months / 12);
  const rest = months % 12;
  const parts = [years > 0 ? `${years}년` : "", rest > 0 ? `${rest}개월` : ""].filter(Boolean);
  return parts.join(" ");
}

export function formatRatio(savingsPct: number): string {
  return `적금 ${savingsPct} : ETF ${100 - savingsPct}`;
}

/** "2026-11" + 36개월 → "2029.11" */
export function formatYearMonth(startMonth: string, plusMonths: number): string {
  const [year, month] = startMonth.split("-").map(Number);
  const index = year * 12 + (month - 1) + plusMonths;
  const targetYear = Math.floor(index / 12);
  const targetMonth = (index % 12) + 1;
  return `${targetYear}.${String(targetMonth).padStart(2, "0")}`;
}
