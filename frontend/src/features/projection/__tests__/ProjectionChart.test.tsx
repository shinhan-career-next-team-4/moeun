// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { formatMoneyShort } from "../formatMoney";
import { MOCK_PROJECTION } from "../mockProjection";
import { ProjectionChart } from "../ProjectionChart";
import type { ProjectionScenario } from "../types";

// "N년 뒤 예상 자산" 누적 그래프 화면(#298). 값은 mock 데이터이고, 화면은 받은 값을 그대로 보여 준다.

const lastOf = (scenario: ProjectionScenario) => MOCK_PROJECTION[scenario][119];
const chart = () => screen.getByRole("group", { name: "예상 자산 누적 그래프" });

function labels(root: HTMLElement, axis: "x" | "y") {
  return [...root.querySelectorAll(`text[data-axis='${axis}']`)].map((node) => node.textContent);
}

describe("예상 자산 누적 그래프 화면 ProjectionChart (#298)", () => {
  afterEach(cleanup);

  it("상단에 'N년 뒤 예상 자산'과 큰 결론 금액, 초록색 '+수익 금액'이 보인다(기준 시나리오)", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);

    const base = lastOf("BASE");
    const headline = screen.getByRole("region", { name: "10년 뒤 예상 자산" });
    expect(within(headline).getByText("10년 뒤 예상 자산")).toBeTruthy();
    expect(within(headline).getByText(`${formatMoneyShort(base.total)}원`)).toBeTruthy();
    const profit = within(headline).getByText(`+${formatMoneyShort(base.profit)}원`);
    expect(profit.className).toContain("text-success");
  });

  it("범례는 그래프 위에 있고 항목 옆에 최종 금액이 보인다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);

    const base = lastOf("BASE");
    const legend = screen.getByRole("list", { name: "범례" });
    expect(within(legend).getByText(`원금 ${formatMoneyShort(base.principal)}`)).toBeTruthy();
    expect(within(legend).getByText(`적금 이자 ${formatMoneyShort(base.savingsInterest)}`)).toBeTruthy();
    expect(within(legend).getByText(`ETF 수익 ${formatMoneyShort(base.etfGain)}`)).toBeTruthy();
    // 범례가 그래프보다 먼저(위에) 나온다
    expect(legend.compareDocumentPosition(chart()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("처음에는 '월별'과 '기준'이 골라져 있다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);

    expect(screen.getByRole("radio", { name: "월별" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByRole("radio", { name: "기준" }).getAttribute("aria-checked")).toBe("true");
  });

  it("시나리오를 바꾸면 상단 숫자·수익·범례가 함께 바뀐다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);

    fireEvent.click(screen.getByRole("radio", { name: "상위 10%" }));

    const high = lastOf("HIGH");
    const headline = screen.getByRole("region", { name: "10년 뒤 예상 자산" });
    expect(within(headline).getByText(`${formatMoneyShort(high.total)}원`)).toBeTruthy();
    expect(within(headline).getByText(`+${formatMoneyShort(high.profit)}원`)).toBeTruthy();
    expect(screen.getByText(`ETF 수익 ${formatMoneyShort(high.etfGain)}`)).toBeTruthy();

    fireEvent.click(screen.getByRole("radio", { name: "하위 10%" }));
    expect(within(headline).getByText(`${formatMoneyShort(lastOf("LOW").total)}원`)).toBeTruthy();
  });

  it("월별/연별은 데이터 단위만 바꾼다: 상단 숫자와 가로 눈금은 그대로이고 점만 12개월 간격이 된다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);
    const before = labels(chart(), "x");

    fireEvent.keyDown(chart(), { key: "End" });
    fireEvent.keyDown(chart(), { key: "ArrowLeft" });
    expect(within(screen.getByRole("tooltip")).getByText("9년 11개월")).toBeTruthy(); // 월별: 119개월째
    fireEvent.keyDown(chart(), { key: "Escape" });

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));
    fireEvent.keyDown(chart(), { key: "End" });
    fireEvent.keyDown(chart(), { key: "ArrowLeft" });
    expect(within(screen.getByRole("tooltip")).getByText("9년")).toBeTruthy(); // 연별: 108개월째

    expect(labels(chart(), "x")).toEqual(before);
    expect(labels(chart(), "x")).toEqual(["1년", "2년", "3년", "4년", "5년", "6년", "7년", "8년", "9년", "10년"]);
    expect(screen.getByText(`${formatMoneyShort(lastOf("BASE").total)}원`)).toBeTruthy();
  });

  it("시나리오를 바꿔도 세로축은 그대로다(축을 매번 새로 잡지 않는다)", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);
    const before = labels(chart(), "y");

    fireEvent.click(screen.getByRole("radio", { name: "하위 10%" }));

    expect(labels(chart(), "y")).toEqual(before);
    expect(before[0]).toBe("0");
  });

  it("시나리오를 바꾸면 그래프가 다시 차오르고, 월별/연별을 바꿀 때는 다시 차오르지 않는다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);
    const first = document.querySelector("[data-reveal]");

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));
    expect(document.querySelector("[data-reveal]")).toBe(first);

    fireEvent.click(screen.getByRole("radio", { name: "상위 10%" }));
    expect(document.querySelector("[data-reveal]")).not.toBe(first);
  });

  it("그래프 아래에 '예상치이며 실제 수익을 보장하지 않습니다.' 안내가 있다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} />);

    const notice = screen.getByText("예상치이며 실제 수익을 보장하지 않습니다.");
    expect(chart().compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("데이터가 비어 있으면 아무것도 그리지 않는다", () => {
    const { container } = render(<ProjectionChart data={{ LOW: [], BASE: [], HIGH: [] }} />);

    expect(container.firstChild).toBeNull();
  });
});
