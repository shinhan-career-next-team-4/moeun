import { isBlank, manwonToWon } from "./units";

/**
 * 적금 : ETF 비중 산출(FR-INP-08, D-085·D-087, 계약 C12a-5-6 (g)).
 *
 * NFR-INT-01의 예외다. 화면은 **비율 하나만** 만들고 금액·차액은 계산하지 않는다.
 * 식은 정수 HALF_UP으로 고정한다: `r = floor((200a + s) / (2s))` (a = 적금 원, s = 적금 + ETF).
 * `Math.round(a / s * 100)` 같은 부동소수 식은 쓰지 않는다.
 */
export type RatioResult =
  | { kind: "ratio"; savingsRatioPct: number }
  /** 두 칸이 모두 비었거나 합이 0 — 칸 비움으로 본다((h)) */
  | { kind: "empty" }
  /** 숫자로 읽을 수 없거나 음수 */
  | { kind: "invalid" };

/** 만원 문자열 두 칸 → 적금 비중(%). 빈 칸은 0으로 보고, 둘 다 비면 "칸 비움"이다. */
export function savingsRatioFromAmounts(bankManwon: string, etfManwon: string): RatioResult {
  const bank = toWon(bankManwon);
  const etf = toWon(etfManwon);
  if (bank === null || etf === null) return { kind: "invalid" };

  const sum = bank + etf;
  if (sum === 0) return { kind: "empty" };
  return { kind: "ratio", savingsRatioPct: halfUpPercent(bank, sum) };
}

/** 정수 HALF_UP 백분율. 부동소수를 쓰지 않는다(D-087). */
function halfUpPercent(part: number, total: number): number {
  return Math.floor((200 * part + total) / (2 * total));
}

function toWon(raw: string): number | null {
  if (isBlank(raw)) return 0;
  const won = manwonToWon(raw);
  return won.ok ? won.won : null;
}
