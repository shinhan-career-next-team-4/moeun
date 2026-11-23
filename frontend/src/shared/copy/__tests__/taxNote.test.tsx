// @vitest-environment jsdom
// Issue #233: 세금 안내는 대화창(AI 답·패널)에 두지 않고 결과 화면 가정 보기에서만 계획에 맞는 문장으로 고지한다.
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { AssumptionsSection } from "../../../features/result/AssumptionsSection";
import type { Assumptions } from "../../../features/result/types";
import { COPY } from "../copy";
import { taxNoteFor } from "../taxNote";

afterEach(cleanup);

const assumptions = (etfGainTaxRatePct: number | null): Assumptions => ({
  savingsRatePct: 3.12,
  isDefaultSavingsRate: false,
  savingsRateBaseMonth: null,
  savingsRatioPct: 50,
  taxRatePct: 15.4,
  scenarioMethod: null,
  sp500BaseDate: null,
  excludedItems: [],
  etfGainTaxRatePct,
});

describe("taxNoteFor", () => {
  it("ETF 차익 세율이 0이면 비과세 안내, 그 밖(과세·ETF 0%·값 없음)은 일반 안내다", () => {
    expect(taxNoteFor(0)).toBe(COPY["tax.noteEtfTaxFree"]);
    expect(taxNoteFor(15.4)).toBe(COPY["tax.note"]);
    expect(taxNoteFor(null)).toBe(COPY["tax.note"]);
    expect(taxNoteFor(undefined)).toBe(COPY["tax.note"]);
  });
});

describe("가정 보기의 세금 안내", () => {
  it("비과세 ETF 계획이면 비과세 안내를 보여 준다", () => {
    render(<AssumptionsSection assumptions={assumptions(0)} display={{}} />);
    expect(screen.getByText(COPY["tax.noteEtfTaxFree"])).toBeTruthy();
    expect(screen.queryByText(COPY["tax.note"])).toBeNull();
  });

  it("과세 ETF·ETF 0% 계획이면 일반 안내를 보여 준다", () => {
    render(<AssumptionsSection assumptions={assumptions(15.4)} display={{}} />);
    expect(screen.getByText(COPY["tax.note"])).toBeTruthy();
    cleanup();
    render(<AssumptionsSection assumptions={assumptions(null)} display={{}} />);
    expect(screen.getByText(COPY["tax.note"])).toBeTruthy();
  });
});
