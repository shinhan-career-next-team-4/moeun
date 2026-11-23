// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "../../../api/errors";
import etfCompareMock from "../../../mocks/chat/post-compare.200-etf.json";
import productsMock from "../../../mocks/refdata/get-etf-products.200.json";
import { EtfCompareSection } from "../EtfCompareSection";

// 결과 화면 ETF 종목 3안(#156). 화면이 뜨면 세 종목을 카드로 바로 보여 주고, 카드를 누르면 바로 적용한다.

const products = productsMock.responses[0].body;
const kospiView = etfCompareMock.responses[0].body;

const CURRENT = "S&P500 추종 ETF";
const KOSPI = "코스피200 추종 ETF";
const NASDAQ = "나스닥100 추종 ETF";

/** 나스닥 비교 응답: 코스피 응답을 복사해 바꾼 쪽의 종목과 값만 다르게 한다 */
function nasdaqView() {
  const body = structuredClone(kospiView) as unknown as {
    changed: { assumptions: Record<string, unknown> };
    display: Record<string, string>;
  };
  body.changed.assumptions.etfProductCode = "133690";
  body.changed.assumptions.etfProductName = NASDAQ;
  body.display["changed.AVG.afterTaxAsset"] = "약 1,724만원";
  body.display["diff.AVG.afterTaxAssetDelta"] = "약 12만원 증가";
  return body;
}

/** 수동으로 풀어 주는 약속. 응답이 날아오는 중의 조작을 재현한다 */
function pending<T>() {
  let release!: (value: T) => void;
  const promise = new Promise<T>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

interface Handlers {
  products?: () => Promise<unknown>;
  compare?: (code: string) => Promise<unknown>;
}

interface Props {
  currentProductName: string;
  currentProductCode?: string;
  revision: number | null;
}

function renderSection(
  handlers: Handlers = {},
  initial: Partial<Props> = {},
  onApply: (request: { changes: { etfProductCode?: string }; revision: number }) => Promise<number | null> = vi.fn(
    async () => 13,
  ),
) {
  const request = vi.fn(async (method: string, path: string, body?: unknown) => {
    if (path === "/api/etf-products") return handlers.products ? await handlers.products() : products;
    const { conditionChanges } = body as { conditionChanges: { etfProductCode: string } };
    const code = conditionChanges.etfProductCode;
    if (handlers.compare) return await handlers.compare(code);
    return code === "133690" ? nasdaqView() : kospiView;
  });
  const base: Props = { currentProductName: CURRENT, revision: 12, ...initial };
  const element = (props: Props) => <EtfCompareSection {...props} onApply={onApply} deps={{ request }} />;
  const view = render(element(base));
  const callsTo = (path: string) => request.mock.calls.filter((call) => call[1] === path);
  const update = (next: Partial<Props>) => view.rerender(element({ ...base, ...next }));
  return { request, callsTo, onApply, view, update };
}

const card = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;
const waitForCards = () => screen.findByRole("button", { name: NASDAQ });

describe("ETF 종목 3안 (#156, C12a-9-3)", () => {
  afterEach(cleanup);

  it("열면 서버가 준 세 종목이 카드로 한 번에 보이고 종목 고르기 칩은 없다", async () => {
    renderSection();
    await waitForCards();

    expect(card(CURRENT)).toBeTruthy();
    expect(card(KOSPI)).toBeTruthy();
    expect(screen.queryByRole("radiogroup")).toBeNull();
    expect(screen.queryByRole("radio")).toBeNull();
  });

  it("지금 종목 카드는 누를 수 없고 다른 두 종목 카드는 누를 수 있다", async () => {
    renderSection();
    await waitForCards();

    expect(card(CURRENT).disabled).toBe(true);
    expect(card(KOSPI).disabled).toBe(false);
    expect(card(NASDAQ).disabled).toBe(false);
  });

  it("지금 종목은 비교를 요청하지 않고 다른 두 종목만 요청한다", async () => {
    const { callsTo } = renderSection();
    await waitForCards();

    const codes = callsTo("/api/compare").map((call) => (call[2] as { conditionChanges: { etfProductCode: string } }).conditionChanges.etfProductCode);
    expect(codes.sort()).toEqual(["069500", "133690"]);
  });

  it("카드마다 서버 display 값이 보인다. 지금 종목은 지금 값, 다른 종목은 바뀐 값과 지금 종목 대비", async () => {
    renderSection();
    await waitForCards();

    expect(card(CURRENT).textContent).toContain("지금 종목");
    expect(card(CURRENT).textContent).toContain("약 1,712만원");
    expect(card(CURRENT).textContent).not.toContain("지금 종목 대비");

    expect(card(KOSPI).textContent).toContain("약 1,689만원");
    expect(card(KOSPI).textContent).toContain("약 23만원 감소");
    expect(card(KOSPI).textContent).toContain("-약 311만원 미달");
    expect(card(KOSPI).textContent).toContain("약 1,602만원");

    expect(card(NASDAQ).textContent).toContain("약 1,724만원");
    expect(card(NASDAQ).textContent).toContain("약 12만원 증가");
  });

  it("카드를 누르면 확인 없이 바로 그 종목과 그때의 revision으로 적용한다", async () => {
    const { onApply } = renderSection();
    await waitForCards();

    fireEvent.click(card(KOSPI));

    await waitFor(() =>
      expect(onApply).toHaveBeenCalledWith({ changes: { etfProductCode: "069500" }, revision: 12 }),
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("적용 중에 카드를 또 눌러도 한 번만 보낸다", async () => {
    const applying = pending<number | null>();
    const { onApply } = renderSection({}, {}, vi.fn(() => applying.promise));
    await waitForCards();

    fireEvent.click(card(KOSPI));
    fireEvent.click(card(NASDAQ));
    fireEvent.click(card(KOSPI));

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(card(NASDAQ).disabled).toBe(true);
    applying.release(null);
  });

  it("화면이 뜨면 열기 단계 없이 바로 카드가 보이고, 목록과 비교는 한 번씩만 받는다", async () => {
    const { callsTo } = renderSection();
    await waitForCards();

    expect(screen.queryByRole("button", { name: "다른 ETF 종목으로 비교해 보기" })).toBeNull();
    expect(screen.queryByRole("button", { name: "닫기" })).toBeNull();
    expect(callsTo("/api/etf-products")).toHaveLength(1);
    expect(callsTo("/api/compare")).toHaveLength(2);
  });

  it("적용하면 카드가 열린 채로, 바뀐 종목이 지금 종목이 되고 나머지 두 종목 결과를 새로 받는다", async () => {
    const { callsTo, update } = renderSection();
    await waitForCards();
    fireEvent.click(card(KOSPI));

    // 적용에 성공하면 결과 화면이 새 revision으로 계획을 다시 읽어 내려 준다
    update({ revision: 13, currentProductName: KOSPI });

    await waitFor(() => expect(callsTo("/api/compare")).toHaveLength(4));
    const codes = callsTo("/api/compare")
      .slice(2)
      .map((call) => (call[2] as { conditionChanges: { etfProductCode: string } }).conditionChanges.etfProductCode);
    expect(codes.sort()).toEqual(["133690", "SP500"]);
    await waitFor(() => expect(card(KOSPI).disabled).toBe(true));
    expect(card(KOSPI).textContent).toContain("지금 종목");
    expect(card(CURRENT).disabled).toBe(false);
    expect(screen.queryByText("종목을 바꿨어요. 이전 종목과 비교해 보세요")).toBeNull();
  });

  it("적용하지 못하면 카드가 그대로 열려 있고 비교표는 없다", async () => {
    const { onApply } = renderSection({}, {}, vi.fn(async () => null));
    await waitForCards();

    fireEvent.click(card(KOSPI));
    await waitFor(() => expect(onApply).toHaveBeenCalled());

    expect(card(KOSPI).disabled).toBe(false);
    expect(screen.queryByText("종목을 바꿨어요. 이전 종목과 비교해 보세요")).toBeNull();
  });

  it("결과를 계산하는 동안에는 계산 중이라고 알린다", async () => {
    const slow = pending<unknown>();
    renderSection({ compare: () => slow.promise });

    expect(await screen.findByText("종목별 결과를 계산하는 중이에요")).toBeTruthy();
    slow.release(kospiView);
    await waitForCards();
    expect(screen.queryByText("종목별 결과를 계산하는 중이에요")).toBeNull();
  });

  it("종목 목록을 못 가져오면 안내와 다시 시도가 보이고, 다시 시도를 연달아 눌러도 요청은 하나다", async () => {
    const second = pending<unknown>();
    let attempt = 0;
    const { callsTo } = renderSection({
      products: () => {
        attempt += 1;
        return attempt === 1 ? Promise.reject(ApiError.network()) : second.promise;
      },
    });
    expect(await screen.findByText("종목 목록을 불러오지 못했어요. 다시 시도해 주세요")).toBeTruthy();
    const retry = screen.getByRole("button", { name: "다시 시도" });

    fireEvent.click(retry);
    fireEvent.click(retry);
    second.release(products);
    await waitForCards();

    expect(callsTo("/api/etf-products")).toHaveLength(2);
  });

  it("종목 목록 응답에 products가 없으면 목록 실패로 다룬다", async () => {
    renderSection({ products: async () => ({}) });

    expect(await screen.findByText("종목 목록을 불러오지 못했어요. 다시 시도해 주세요")).toBeTruthy();
    expect(screen.queryByRole("button", { name: CURRENT })).toBeNull();
  });

  it("고를 수 있는 종목이 내 종목 하나뿐이면 비교할 종목이 없다고 안내한다", async () => {
    renderSection({ products: async () => ({ products: [products.products[0]] }) });

    expect(await screen.findByText("지금은 비교할 다른 종목이 없어요")).toBeTruthy();
    expect(screen.queryByRole("button", { name: CURRENT })).toBeNull();
  });

  it("한 종목의 비교가 실패하면 그 카드에만 이유가 보이고 눌 수 없다", async () => {
    renderSection({
      compare: (code) =>
        code === "133690"
          ? Promise.reject(
              ApiError.fromProblem(422, {
                code: "VALIDATION_FAILED",
                errors: [{ field: "etfProductCode", reason: "NOT_AVAILABLE" }],
              }),
            )
          : Promise.resolve(kospiView),
    });
    await waitForCards();

    expect(card(NASDAQ).disabled).toBe(true);
    expect(card(NASDAQ).textContent).toContain("그 종목은 아직 계산할 수 없어요");
    expect(card(KOSPI).disabled).toBe(false);
  });

  it("열려 있는 동안 내 계획이 바뀌면 카드를 막고 다시 비교하라고 안내한다", async () => {
    const { callsTo, update } = renderSection();
    await waitForCards();

    update({ revision: 13 });

    expect(screen.getByText("내 계획이 바뀌었어요. 다시 비교해 주세요")).toBeTruthy();
    expect(screen.queryByRole("button", { name: KOSPI })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitForCards();

    expect(callsTo("/api/compare")).toHaveLength(4);
    expect(card(KOSPI).disabled).toBe(false);
  });

  it("지금 종목은 이름이 아니라 종목 코드로 찾는다", async () => {
    const { callsTo } = renderSection({}, { currentProductName: "에스앤피500 추종 ETF", currentProductCode: "SP500" });
    await waitForCards();

    expect(card(CURRENT).disabled).toBe(true);
    expect(card(CURRENT).textContent).toContain("지금 종목");
    expect(callsTo("/api/compare")).toHaveLength(2);
  });

  it("ETF 0% 계획(종목 이름 없음)에서는 아무것도 그리지 않는다", () => {
    const { view, callsTo } = renderSection({}, { currentProductName: "" });

    expect(view.container.firstChild).toBeNull();
    expect(callsTo("/api/etf-products")).toHaveLength(0);
  });
});
