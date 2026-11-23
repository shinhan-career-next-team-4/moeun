// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "../../../api/errors";
import etfCompareMock from "../../../mocks/chat/post-compare.200-etf.json";
import productsMock from "../../../mocks/refdata/get-etf-products.200.json";
import { EtfCompareSection } from "../EtfCompareSection";

const products = productsMock.responses[0].body;
const comparison = etfCompareMock.responses[0].body;

const CURRENT = "S&P500 추종 ETF";
const KOSPI = "코스피200 추종 ETF";
const NASDAQ = "나스닥100 추종 ETF";

interface Handlers {
  products?: () => Promise<unknown>;
  compare?: (code: string) => Promise<unknown>;
}

function deps({ products: onProducts, compare: onCompare }: Handlers = {}) {
  const request = vi.fn(async (method: string, path: string, body?: unknown) => {
    if (path === "/api/etf-products") return onProducts ? await onProducts() : products;
    const { conditionChanges } = body as { conditionChanges: { etfProductCode: string } };
    return onCompare ? await onCompare(conditionChanges.etfProductCode) : comparison;
  });
  return { request };
}

function renderSection(
  handlers: Handlers = {},
  props: { currentProductName?: string; revision?: number | null } = {},
) {
  const fake = deps(handlers);
  const view = render(
    <EtfCompareSection
      currentProductName={props.currentProductName ?? CURRENT}
      scenarioMethod="ROLLING_DISTRIBUTION"
      revision={props.revision ?? 12}
      deps={fake}
    />,
  );
  const callsTo = (path: string) => fake.request.mock.calls.filter((call) => call[1] === path);
  return { request: fake.request, callsTo, view };
}

/** 칩이 그려질 때까지 기다린다(목록은 영역을 열 때 가져온다). */
function waitForChips() {
  return screen.findByRole("radio", { name: CURRENT });
}

function open() {
  fireEvent.click(screen.getByRole("button", { name: "다른 ETF 종목으로 비교해 보기" }));
}

function pick(name: string) {
  fireEvent.click(screen.getByRole("radio", { name }));
}

describe("ETF 종목 비교 영역 (#156, C12a-9-3)", () => {
  afterEach(cleanup);

  it("닫혀 있을 때는 지금 종목과 여는 단추만 보인다", () => {
    renderSection();

    expect(screen.getByText(`지금 종목: ${CURRENT}`)).toBeTruthy();
    expect(screen.getByRole("button", { name: "다른 ETF 종목으로 비교해 보기" })).toBeTruthy();
    expect(screen.queryByRole("radiogroup")).toBeNull();
  });

  it("열면 서버가 준 종목이 모두 보이고 지금 종목이 선택돼 있다", async () => {
    renderSection();
    open();

    expect((await waitForChips()).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByRole("radio", { name: KOSPI }).getAttribute("aria-checked")).toBe("false");
    expect(screen.getByRole("radio", { name: NASDAQ })).toBeTruthy();
  });

  it("다른 종목을 고르면 비교표가 보인다. 요청은 그 종목 코드로 간다", async () => {
    const { request } = renderSection();
    open();
    await waitForChips();
    pick(KOSPI);

    expect(await screen.findByRole("rowheader", { name: "ETF 종목" })).toBeTruthy();
    expect(screen.getByRole("rowheader", { name: "세후 예상 자산(평균)" }).closest("tr")?.textContent).toContain(
      "약 1,689만원",
    );
    expect(request).toHaveBeenCalledWith("POST", "/api/compare", {
      conditionChanges: { etfProductCode: "069500" },
    });
  });

  it("고른 종목 이름으로 구간 산정 문구가 바뀐다", async () => {
    renderSection();
    open();
    await waitForChips();
    pick(KOSPI);

    expect(
      await screen.findByText(
        `기준 종목 ${KOSPI} · 과거 10년 수익률로 1만 가지 경우를 계산해 세 구간 (가정 보기 참고)`,
      ),
    ).toBeTruthy();
  });

  it("지금 종목을 다시 고르면 표를 지우고 같은 종목이라고 안내한다", async () => {
    const { callsTo } = renderSection();
    open();
    await waitForChips();
    pick(KOSPI);
    await screen.findByRole("rowheader", { name: "ETF 종목" });

    pick(CURRENT);

    expect(await screen.findByText("지금 계획과 같은 종목이에요. 다른 종목을 골라 보세요")).toBeTruthy();
    expect(screen.queryByRole("rowheader", { name: "ETF 종목" })).toBeNull();
    expect(callsTo("/api/compare")).toHaveLength(1);
  });

  it("닫고 다시 열어도 종목 목록은 한 번만 가져온다", async () => {
    const { callsTo } = renderSection();
    open();
    await waitForChips();

    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    open();
    await waitForChips();

    expect(callsTo("/api/etf-products")).toHaveLength(1);
  });

  it("종목을 연달아 고르면 마지막으로 고른 종목의 표만 남는다", async () => {
    let releaseFirst: (() => void) | null = null;
    const first = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    renderSection({
      compare: async (code) => {
        if (code === "069500") {
          await first;
          return { ...comparison, changed: { assumptions: { etfProductName: KOSPI } } };
        }
        return { ...comparison, changed: { assumptions: { etfProductName: NASDAQ } } };
      },
    });
    open();
    await waitForChips();
    pick(KOSPI);
    pick(NASDAQ);
    await screen.findByRole("rowheader", { name: "ETF 종목" });

    releaseFirst?.();

    await waitFor(() => {
      const row = screen.getByRole("rowheader", { name: "ETF 종목" }).closest("tr");
      expect(row?.textContent).toContain(NASDAQ);
    });
    expect(screen.getByRole("rowheader", { name: "ETF 종목" }).closest("tr")?.textContent).not.toContain(KOSPI);
  });

  it("내 계획이 바뀌면(revision) 비교표를 지운다", async () => {
    const { view } = renderSection();
    open();
    await waitForChips();
    pick(KOSPI);
    await screen.findByRole("rowheader", { name: "ETF 종목" });

    view.rerender(
      <EtfCompareSection
        currentProductName={CURRENT}
        scenarioMethod="ROLLING_DISTRIBUTION"
        revision={13}
        deps={deps()}
      />,
    );

    expect(screen.queryByRole("rowheader", { name: "ETF 종목" })).toBeNull();
  });

  it("종목 목록을 못 가져오면 안내와 다시 시도가 보인다", async () => {
    renderSection({ products: () => Promise.reject(ApiError.network()) });
    open();

    expect(await screen.findByText("종목 목록을 불러오지 못했어요. 다시 시도해 주세요")).toBeTruthy();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeTruthy();
    expect(screen.queryByRole("radiogroup")).toBeNull();
  });

  it("적재하지 않은 종목이면 안내만 보이고 표는 없다", async () => {
    renderSection({
      compare: () =>
        Promise.reject(
          ApiError.fromProblem(422, {
            code: "VALIDATION_FAILED",
            errors: [{ field: "etfProductCode", reason: "NOT_AVAILABLE" }],
          }),
        ),
    });
    open();
    await waitForChips();
    pick(KOSPI);

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("그 종목은 아직 계산할 수 없어요");
    expect(screen.queryByRole("rowheader", { name: "ETF 종목" })).toBeNull();
  });

  it("고를 수 있는 종목이 내 종목 하나뿐이면 비교할 종목이 없다고 안내한다", async () => {
    renderSection({ products: async () => ({ products: [products.products[0]] }) });
    open();

    expect(await screen.findByText("지금은 비교할 다른 종목이 없어요")).toBeTruthy();
    expect(screen.queryByRole("radiogroup")).toBeNull();
  });

  it("ETF 0% 계획(종목 이름 없음)에서는 아무것도 그리지 않는다", () => {
    const { view } = renderSection({}, { currentProductName: "" });

    expect(view.container.firstChild).toBeNull();
  });
});
