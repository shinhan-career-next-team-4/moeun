// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import etfCompareMock from "../../../mocks/chat/post-compare.200-etf.json";
import { CompareTable } from "../CompareTable";
import type { ComparisonView } from "../types";

const etfView = etfCompareMock.responses[0].body as unknown as ComparisonView;

function rowOf(label: string): HTMLElement {
  const header = screen.getByRole("rowheader", { name: label });
  const row = header.closest("tr");
  if (row === null) throw new Error(`${label} 줄을 찾지 못했다`);
  return row;
}

describe("비교표의 ETF 종목 줄 (#156, C12a-9-3)", () => {
  afterEach(cleanup);

  it("종목을 바꾼 비교면 현재·바꾼 종목의 화면 이름이 보인다", () => {
    render(<CompareTable view={etfView} />);

    const row = rowOf("ETF 종목");
    expect(row.textContent).toContain("S&P500 추종 ETF");
    expect(row.textContent).toContain("코스피200 추종 ETF");
  });

  it("적용 뒤에는 바뀐 조건이 먼저, 이전 조건이 그다음 열이다", () => {
    render(<CompareTable view={etfView} variant="applied" />);

    const headers = screen.getAllByRole("columnheader").map((header) => header.textContent);
    expect(headers).toEqual(["항목", "바뀐 조건", "이전 조건", "변화"]);
    const cells = within(rowOf("ETF 종목")).getAllByRole("cell").map((cell) => cell.textContent);
    expect(cells).toEqual(["코스피200 추종 ETF", "S&P500 추종 ETF", "—"]);
  });

  it("종목 줄과 함께 결과 줄도 서버 display 문자열로 보인다", () => {
    render(<CompareTable view={etfView} />);

    const row = rowOf("세후 예상 자산(평균)");
    expect(row.textContent).toContain("약 1,712만원");
    expect(row.textContent).toContain("약 1,689만원");
    expect(row.textContent).toContain("약 23만원 감소");
  });
});
