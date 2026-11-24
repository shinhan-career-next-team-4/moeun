// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { AreaChart } from "../AreaChart";
import { niceTicks } from "../axis";
import { formatMoneyShort } from "../formatMoney";
import { chartLayout, monthToX, valueToY } from "../layout";
import { MOCK_PROJECTION } from "../mockProjection";

// 누적 영역 그래프(#298). 데이터는 mock이고, 좌표는 layout.ts와 같은 식으로 검증한다.

const AVG = MOCK_PROJECTION.AVG;
const MAX_VALUE = MOCK_PROJECTION.GOOD[119].total;
const RANGE = { min: 1, max: 120 };
const WIDTH = 640; // jsdom은 폭을 재지 못해 컴포넌트가 이 값을 기본으로 쓴다

function renderChart(overrides: Partial<React.ComponentProps<typeof AreaChart>> = {}) {
  const props = { points: AVG, monthRange: RANGE, maxValue: MAX_VALUE, revealKey: "AVG", ariaLabel: "예상 자산 그래프" };
  const view = render(<AreaChart {...props} {...overrides} />);
  const chart = screen.getByRole("group", { name: "예상 자산 그래프" });
  return { chart, view, props };
}

describe("누적 영역 그래프 AreaChart (#298)", () => {
  beforeEach(() => {
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      left: 0, top: 0, right: WIDTH, bottom: 300, width: WIDTH, height: 300, x: 0, y: 0, toJSON: () => ({}),
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("세로축은 0부터 시작하고 눈금이 4~5개이며 금액을 줄여서 쓴다", () => {
    const { chart } = renderChart();

    const labels = [...chart.querySelectorAll<SVGTextElement>("text[data-axis='y']")];
    const ticks = niceTicks(MAX_VALUE);
    expect(labels.length).toBe(ticks.length);
    expect(labels.length).toBeGreaterThanOrEqual(4);
    expect(labels.length).toBeLessThanOrEqual(5);
    expect(labels.map((l) => l.textContent)).toEqual(ticks.map(formatMoneyShort));
    // 0이 가장 아래(y 좌표가 가장 크다)에 있다
    const ys = labels.map((l) => Number(l.getAttribute("y")));
    expect(labels[0].textContent).toBe("0");
    expect(ys[0]).toBe(Math.max(...ys));
  });

  it("가로축은 월별 데이터여도 1년부터 10년까지 눈금만 보인다", () => {
    const { chart } = renderChart();

    const labels = [...chart.querySelectorAll("text[data-axis='x']")].map((l) => l.textContent);
    expect(labels).toEqual(["1년", "2년", "3년", "4년", "5년", "6년", "7년", "8년", "9년", "10년"]);
  });

  it("격자선은 가로선만 있고 세로선은 없다", () => {
    const { chart } = renderChart();

    const grid = [...chart.querySelectorAll("line[data-grid]")];
    expect(grid.length).toBe(niceTicks(MAX_VALUE).length);
    for (const line of grid) {
      expect(line.getAttribute("data-grid")).toBe("horizontal");
      expect(line.getAttribute("y1")).toBe(line.getAttribute("y2"));
    }
  });

  it("원금 → 적금 이자 → ETF 수익 순서로 아래부터 쌓이고, 층마다 2px 테두리 선이 있다", () => {
    const { chart } = renderChart();

    const layers = [...chart.querySelectorAll("path[data-layer]")].map((p) => p.getAttribute("data-layer"));
    expect(layers).toEqual(["principal", "savingsInterest", "etfGain"]);
    for (const name of ["principal", "savingsInterest", "etfGain"]) {
      const line = chart.querySelector(`path[data-line='${name}']`);
      expect(line?.getAttribute("stroke-width")).toBe("2");
      expect(line?.getAttribute("fill")).toBe("none");
    }
  });

  it("맨 위 선의 끝점은 마지막 달의 (원금 + 적금 이자 + ETF 수익) 높이에 있다", () => {
    const { chart } = renderChart();
    const layout = chartLayout(WIDTH);
    const last = AVG[AVG.length - 1];
    const top = niceTicks(MAX_VALUE).at(-1) as number;

    const d = chart.querySelector("path[data-line='etfGain']")?.getAttribute("d") ?? "";
    const nums = [...d.matchAll(/-?\d+(?:\.\d+)?/g)].map((m) => Number(m[0]));
    const [endX, endY] = nums.slice(-2);
    expect(endX).toBeCloseTo(monthToX(120, RANGE, layout), 1);
    expect(endY).toBeCloseTo(valueToY(last.principal + last.savingsInterest + last.etfGain, top, layout), 1);
  });

  it("포인터를 올리면 그 시점의 원금·적금 이자·ETF 수익과 합계가 툴팁으로 보인다", () => {
    const { chart } = renderChart();
    const layout = chartLayout(WIDTH);
    const p = AVG[59]; // 60개월째

    fireEvent.pointerMove(chart, { clientX: monthToX(60, RANGE, layout) });

    const tip = within(screen.getByRole("tooltip"));
    expect(tip.getByText("5년")).toBeTruthy();
    expect(tip.getByText("원금").parentElement?.textContent).toContain(formatMoneyShort(p.principal));
    expect(tip.getByText("적금 이자").parentElement?.textContent).toContain(formatMoneyShort(p.savingsInterest));
    expect(tip.getByText("ETF 수익").parentElement?.textContent).toContain(formatMoneyShort(p.etfGain));
    expect(tip.getByText("합계").parentElement?.textContent).toContain(formatMoneyShort(p.total));

    fireEvent.pointerLeave(chart);
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("툴팁 제목은 12개월 미만이면 'N개월', 딱 떨어지면 'N년', 아니면 'N년 M개월'이다", () => {
    const { chart } = renderChart();
    const layout = chartLayout(WIDTH);

    fireEvent.pointerMove(chart, { clientX: monthToX(1, RANGE, layout) });
    expect(within(screen.getByRole("tooltip")).getByText("1개월")).toBeTruthy();

    fireEvent.pointerMove(chart, { clientX: monthToX(62, RANGE, layout) });
    expect(within(screen.getByRole("tooltip")).getByText("5년 2개월")).toBeTruthy();
  });

  it("키보드: Home·End·방향키로 시점을 옮기고 Esc로 닫는다", () => {
    const { chart } = renderChart();

    fireEvent.keyDown(chart, { key: "Home" });
    expect(within(screen.getByRole("tooltip")).getByText("1개월")).toBeTruthy();

    fireEvent.keyDown(chart, { key: "End" });
    expect(within(screen.getByRole("tooltip")).getByText("10년")).toBeTruthy();

    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(within(screen.getByRole("tooltip")).getByText("9년 11개월")).toBeTruthy();

    fireEvent.keyDown(chart, { key: "Escape" });
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("revealKey가 바뀌면 그래프가 처음부터 다시 차오르고, 같으면 그대로다", () => {
    const { view, props } = renderChart();
    const first = document.querySelector("[data-reveal]");

    view.rerender(<AreaChart {...props} revealKey="AVG" />);
    expect(document.querySelector("[data-reveal]")).toBe(first);

    view.rerender(<AreaChart {...props} revealKey="GOOD" />);
    expect(document.querySelector("[data-reveal]")).not.toBe(first);
  });

  it("360px 폭에서는 그 폭에 맞춰 그려 가로로 넘치지 않는다", () => {
    class FakeResizeObserver {
      constructor(private callback: ResizeObserverCallback) {}
      observe() {
        this.callback([{ contentRect: { width: 360 } } as ResizeObserverEntry], this as unknown as ResizeObserver);
      }
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal("ResizeObserver", FakeResizeObserver);

    const { chart } = renderChart();

    const svg = chart.querySelector("svg");
    expect(svg?.getAttribute("width")).toBe("360");
    expect(svg?.getAttribute("viewBox")).toBe("0 0 360 300");
  });

  it("세로 420px로 그린다", () => {
    const { chart } = renderChart();

    expect(chart.querySelector("svg")?.getAttribute("viewBox")).toBe("0 0 640 420");
  });

  it("좁은 화면에서는 가로축 라벨이 서로 겹치지 않게 2년 간격으로 솎는다", () => {
    class FakeResizeObserver {
      constructor(private callback: ResizeObserverCallback) {}
      observe() {
        this.callback([{ contentRect: { width: 300 } } as ResizeObserverEntry], this as unknown as ResizeObserver);
      }
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal("ResizeObserver", FakeResizeObserver);

    const { chart } = renderChart();

    const labels = [...chart.querySelectorAll("text[data-axis='x']")].map((l) => l.textContent);
    expect(labels).toEqual(["2년", "4년", "6년", "8년", "10년"]);
  });
});
