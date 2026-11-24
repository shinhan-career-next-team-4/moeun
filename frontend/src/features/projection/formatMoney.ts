const WON_PER_MAN = 10_000;
const MAN_PER_EOK = 10_000;

const withCommas = (n: number) => n.toLocaleString("ko-KR");

/**
 * 금액을 줄여서 쓴다(#298). 만 단위로 반올림한다.
 * 59,999,999 → "6,000만", 120,000,000 → "1억 2,000만", 100,000,000 → "1억", 4,999 → "0".
 * 서버가 준 숫자의 표기만 바꾸며, 값을 더하거나 고치지 않는다.
 */
export function formatMoneyShort(won: number): string {
  const man = Math.round(Math.abs(won) / WON_PER_MAN);
  if (man === 0) return "0";

  const sign = won < 0 ? "-" : "";
  const eok = Math.floor(man / MAN_PER_EOK);
  const rest = man % MAN_PER_EOK;

  if (eok === 0) return `${sign}${withCommas(rest)}만`;
  if (rest === 0) return `${sign}${withCommas(eok)}억`;
  return `${sign}${withCommas(eok)}억 ${withCommas(rest)}만`;
}
