import { parseDecimal, parseManwon } from "./parseManwon";

describe("parseManwon", () => {
  it("쉼표·공백·전각 숫자를 정리해 원으로 바꾼다", () => {
    expect(parseManwon(" 1,000 ")).toEqual({ ok: true, won: 10_000_000 });
    expect(parseManwon("１２０")).toEqual({ ok: true, won: 1_200_000 });
    expect(parseManwon("0")).toEqual({ ok: true, won: 0 });
  });

  it("소수·음수·문자·빈 값은 실패한다", () => {
    expect(parseManwon("1.5").ok).toBe(false);
    expect(parseManwon("-5").ok).toBe(false);
    expect(parseManwon("abc").ok).toBe(false);
    expect(parseManwon("").ok).toBe(false);
  });
});

describe("parseDecimal", () => {
  it("소수를 허용하고 문자는 실패한다", () => {
    expect(parseDecimal("3.25")).toEqual({ ok: true, value: 3.25 });
    expect(parseDecimal(" ３.２ ")).toEqual({ ok: true, value: 3.2 });
    expect(parseDecimal("3.2%").ok).toBe(false);
    expect(parseDecimal("-1").ok).toBe(false);
  });
});
