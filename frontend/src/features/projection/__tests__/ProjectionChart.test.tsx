// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { formatMoneyShort } from "../formatMoney";
import { buildMockProjection, MOCK_PROJECTION } from "../mockProjection";
import { ProjectionChart } from "../ProjectionChart";
import type { ProjectionScenario } from "../types";

// "N년 뒤 예상 자산" 누적 그래프 화면(#298). 값은 mock 데이터이고, 화면은 받은 값을 그대로 보여 준다.
// 시나리오는 위쪽 3구간 카드가 고르므로 이 화면은 `scenario`를 받아 그리기만 한다.

const lastOf = (scenario: ProjectionScenario) => MOCK_PROJECTION[scenario][119];
const chart = () => screen.getByRole("group", { name: "예상 자산 누적 그래프" });

function labels(root: HTMLElement, axis: "x" | "y") {
  return [...root.querySelectorAll(`text[data-axis='${axis}']`)].map((node) => node.textContent);
}

describe("예상 자산 누적 그래프 화면 ProjectionChart (#298)", () => {
  afterEach(cleanup);

  it("상단에 'N년 뒤 예상 자산'과 큰 결론 금액, 초록색 '+수익 금액'이 보인다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);

    const avg = lastOf("AVG");
    const headline = screen.getByRole("region", { name: "10년 뒤 예상 자산" });
    expect(within(headline).getByText("10년 뒤 예상 자산")).toBeTruthy();
    expect(within(headline).getByText(`${formatMoneyShort(avg.total)}원`)).toBeTruthy();
    const profit = within(headline).getByText(`+${formatMoneyShort(avg.profit)}원`);
    expect(profit.className).toContain("text-success");
  });

  it("범례는 그래프 위에 있고 항목 옆에 최종 금액이 보인다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);

    const avg = lastOf("AVG");
    const legend = screen.getByRole("list", { name: "범례" });
    expect(within(legend).getByText(`원금 ${formatMoneyShort(avg.principal)}`)).toBeTruthy();
    expect(within(legend).getByText(`적금 이자 ${formatMoneyShort(avg.savingsInterest)}`)).toBeTruthy();
    expect(within(legend).getByText(`ETF 수익 ${formatMoneyShort(avg.etfGain)}`)).toBeTruthy();
    expect(legend.compareDocumentPosition(chart()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("시나리오 버튼은 이 화면에 없고 월별/연별 토글만 있다(시나리오는 3구간 카드가 고른다)", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);

    expect(screen.queryByRole("radiogroup", { name: "시나리오" })).toBeNull();
    expect(screen.queryByRole("radio", { name: "하위 10%" })).toBeNull();
    expect(screen.queryByRole("radio", { name: "상위 10%" })).toBeNull();
    expect(screen.getByRole("radio", { name: "월별" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByRole("radio", { name: "연별" })).toBeTruthy();
  });

  it("scenario가 바뀌면 상단 숫자·수익·범례가 함께 바뀐다", () => {
    const { rerender } = render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);

    rerender(<ProjectionChart data={MOCK_PROJECTION} scenario="GOOD" />);

    const good = lastOf("GOOD");
    const headline = screen.getByRole("region", { name: "10년 뒤 예상 자산" });
    expect(within(headline).getByText(`${formatMoneyShort(good.total)}원`)).toBeTruthy();
    expect(within(headline).getByText(`+${formatMoneyShort(good.profit)}원`)).toBeTruthy();
    expect(screen.getByText(`ETF 수익 ${formatMoneyShort(good.etfGain)}`)).toBeTruthy();

    rerender(<ProjectionChart data={MOCK_PROJECTION} scenario="BAD" />);
    expect(within(headline).getByText(`${formatMoneyShort(lastOf("BAD").total)}원`)).toBeTruthy();
  });

  it("월별/연별은 데이터 단위만 바꾼다: 상단 숫자와 가로 눈금은 그대로이고 점만 12개월 간격이 된다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);
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
    expect(screen.getByText(`${formatMoneyShort(lastOf("AVG").total)}원`)).toBeTruthy();
  });

  it("시나리오가 바뀌어도 세로축은 그대로다(축을 매번 새로 잡지 않는다)", () => {
    const { rerender } = render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);
    const before = labels(chart(), "y");

    rerender(<ProjectionChart data={MOCK_PROJECTION} scenario="BAD" />);

    expect(labels(chart(), "y")).toEqual(before);
    expect(before[0]).toBe("0");
  });

  it("시나리오가 바뀌면 그래프가 다시 차오르고, 월별/연별을 바꿀 때는 다시 차오르지 않는다", () => {
    const { rerender } = render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);
    const first = document.querySelector("[data-reveal]");

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));
    expect(document.querySelector("[data-reveal]")).toBe(first);

    rerender(<ProjectionChart data={MOCK_PROJECTION} scenario="GOOD" />);
    expect(document.querySelector("[data-reveal]")).not.toBe(first);
  });

  it("그래프 아래에 '예상치이며 실제 수익을 보장하지 않습니다.' 안내가 있다", () => {
    render(<ProjectionChart data={MOCK_PROJECTION} scenario="AVG" />);

    const notice = screen.getByText("예상치이며 실제 수익을 보장하지 않습니다.");
    expect(chart().compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("데이터가 비어 있으면 아무것도 그리지 않는다", () => {
    const { container } = render(<ProjectionChart data={{ GOOD: [], AVG: [], BAD: [] }} scenario="AVG" />);

    expect(container.firstChild).toBeNull();
  });
});

describe("30년(360개월)까지 받는다 (#298)", () => {
  afterEach(cleanup);

  const thirty = buildMockProjection(30);
  const last = thirty.AVG[359];

  it("상단은 '30년 뒤 예상 자산'과 360개월째 합계를 보인다", () => {
    render(<ProjectionChart data={thirty} scenario="AVG" />);

    const headline = screen.getByRole("region", { name: "30년 뒤 예상 자산" });
    expect(within(headline).getByText(`${formatMoneyShort(last.total)}원`)).toBeTruthy();
  });

  it("360개월을 한 점도 빼지 않고 그린다(곡선 조각 359개)", () => {
    render(<ProjectionChart data={thirty} scenario="AVG" />);

    const d = chart().querySelector("path[data-line='etfGain']")?.getAttribute("d") ?? "";
    expect(d.split("C").length - 1).toBe(359);
  });

  it("가로축 라벨은 10개를 넘지 않게 3년 간격으로 찍는다", () => {
    render(<ProjectionChart data={thirty} scenario="AVG" />);

    expect(labels(chart(), "x")).toEqual(["3년", "6년", "9년", "12년", "15년", "18년", "21년", "24년", "27년", "30년"]);
  });

  it("세로축은 0부터 시작하고 억 단위까지 줄여 쓴 눈금이 4~5개다", () => {
    render(<ProjectionChart data={thirty} scenario="AVG" />);

    const ys = labels(chart(), "y");
    expect(ys[0]).toBe("0");
    expect(ys.length).toBeGreaterThanOrEqual(4);
    expect(ys.length).toBeLessThanOrEqual(5);
    expect(ys.at(-1)).toMatch(/억/);
  });

  it("툴팁은 월별이면 '29년 11개월', 연별이면 '29년'까지 옮겨 갈 수 있다", () => {
    render(<ProjectionChart data={thirty} scenario="AVG" />);

    fireEvent.keyDown(chart(), { key: "End" });
    expect(within(screen.getByRole("tooltip")).getByText("30년")).toBeTruthy();
    fireEvent.keyDown(chart(), { key: "ArrowLeft" });
    expect(within(screen.getByRole("tooltip")).getByText("29년 11개월")).toBeTruthy();
    fireEvent.keyDown(chart(), { key: "Escape" });

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));
    fireEvent.keyDown(chart(), { key: "End" });
    fireEvent.keyDown(chart(), { key: "ArrowLeft" });
    expect(within(screen.getByRole("tooltip")).getByText("29년")).toBeTruthy();
  });
});
