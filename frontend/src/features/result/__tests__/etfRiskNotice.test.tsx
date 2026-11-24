// @vitest-environment jsdom
// Issue #374: ETF 원금 미보장 문구와 운용사 공시 출처를 결과 화면에 고정한다(FR-AI-03, D-028, D-104).
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { COPY } from "../../../shared/copy/copy";
import { EtfRiskNotice } from "../EtfRiskNotice";

afterEach(cleanup);

describe("ETF 위험 고정 블록 (#374)", () => {
  it("S&P500 계획이면 원금 미보장 문구와 서로 다른 운용사 공시 2곳의 링크가 보인다", () => {
    render(<EtfRiskNotice etfProductCode="SP500" />);

    const notice = screen.getByRole("note", { name: COPY["result.etfDisclosureLabel"] });
    expect(within(notice).getByText(COPY["result.etfPrincipalRisk"])).toBeTruthy();
    const links = within(notice).getAllByRole("link");
    expect(links.map((a) => a.textContent)).toEqual(["KODEX 미국S&P500 투자설명서", "TIGER 미국S&P500 투자설명서"]);
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      "https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf",
      "https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf",
    ]);
    expect(within(notice).getByText("삼성자산운용")).toBeTruthy();
    expect(within(notice).getByText("미래에셋자산운용")).toBeTruthy();
  });

  it("링크는 새 탭으로 열리고, 기관명은 링크 글자에 섞지 않는다(D-028)", () => {
    render(<EtfRiskNotice etfProductCode="SP500" />);

    for (const link of screen.getAllByRole("link")) {
      expect(link.getAttribute("target")).toBe("_blank");
      expect(link.getAttribute("rel")).toBe("noopener noreferrer");
      expect(link.textContent).not.toMatch(/자산운용/);
    }
  });

  it("코스피200·나스닥100 계획이면 그 종목의 공시만 보인다", () => {
    const { unmount } = render(<EtfRiskNotice etfProductCode="069500" />);
    expect(screen.getAllByRole("link").map((a) => [a.textContent, a.getAttribute("href")])).toEqual([
      ["KODEX 200 투자설명서", "https://www.samsungfund.com/upload/invest/2ETF01-A.pdf"],
    ]);
    expect(screen.getByText("삼성자산운용")).toBeTruthy();
    unmount();

    render(<EtfRiskNotice etfProductCode="133690" />);
    expect(screen.getAllByRole("link").map((a) => [a.textContent, a.getAttribute("href")])).toEqual([
      [
        "TIGER 미국나스닥100 투자설명서",
        "https://investments.miraeasset.com/tigeretf/upload/etf/20260528095823000271.pdf",
      ],
    ]);
    expect(screen.getByText("미래에셋자산운용")).toBeTruthy();
  });

  it("본문 문장에는 상품명을 쓰지 않는다(링크 글자만 상품명, D-028)", () => {
    render(<EtfRiskNotice etfProductCode="SP500" />);

    const sentence = screen.getByText(COPY["result.etfPrincipalRisk"]);
    expect(sentence.textContent).not.toMatch(/KODEX|TIGER/);
  });

  it("(예외) ETF 종목이 없는 계획(적금 100%)이면 아무것도 그리지 않는다", () => {
    const { container } = render(<EtfRiskNotice etfProductCode={null} />);

    expect(container.textContent).toBe("");
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("(예외) 공시 목록에 없는 새 종목이면 원금 미보장 문구는 보이고 링크는 없다", () => {
    render(<EtfRiskNotice etfProductCode="NEW_ETF" />);

    expect(screen.getByText(COPY["result.etfPrincipalRisk"])).toBeTruthy();
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });
});
