// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import NavigateBridge from "../../../app/NavigateBridge";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import etfCompareMock from "../../../mocks/chat/post-compare.200-etf.json";
import productsMock from "../../../mocks/refdata/get-etf-products.200.json";
import planMock from "../../../mocks/plan/get-plan.200.json";
import afterApplyMock from "../../../mocks/plan/get-plan.200-after-apply.json";
import { ResultPage } from "../ResultPage";

// 결과 화면 ETF 종목 3안(#156 2단계, C12a-9-3): 열면 세 종목 카드 -> 카드를 누르면 확인 없이 바로 적용(P-6).
// 적용하면 화면의 ETF 기준 값이 모두 새 종목 값으로 바뀌고, 바뀐 조건과 이전 조건을 비교해 보여 준다.

const SP500 = "S&P500 추종 ETF";
const NASDAQ = "나스닥100 추종 ETF";

/** 계획 응답에 종목 display 키를 넣는다. 목에는 아직 없다(#156 1단계 목 반영은 별도 PR) */
function planWith(mock: typeof planMock, productName: string) {
  const body = structuredClone(mock.responses[0].body) as { display: Record<string, string> };
  body.display["assumptions.etfProductName"] = productName;
  body.display["assumptions.etfGainTaxRatePct"] = "15.4%";
  return body;
}

/** 나스닥을 고른 비교 응답. changed 쪽만 종목이 다르다 */
function nasdaqComparison() {
  const body = structuredClone(etfCompareMock.responses[0].body) as {
    changed: { assumptions: { etfProductCode: string; etfProductName: string } };
  };
  body.changed.assumptions.etfProductCode = "133690";
  body.changed.assumptions.etfProductName = NASDAQ;
  return body;
}

function jsonResponse(status: number, body: unknown): Response {
  const contentType = status >= 400 ? "application/problem+json" : "application/json";
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

interface Call {
  method: string;
  path: string;
  body: unknown;
}

function stubServer(applyResponse: () => Response = () => jsonResponse(200, { revision: 13 })) {
  const calls: Call[] = [];
  let planReads = 0;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    calls.push({ method, path: url.pathname, body });
    if (method === "GET" && url.pathname === "/api/plan") {
      planReads += 1;
      return planReads === 1
        ? jsonResponse(200, planWith(planMock, SP500))
        : jsonResponse(200, planWith(afterApplyMock, NASDAQ));
    }
    if (method === "GET" && url.pathname === "/api/etf-products") {
      return jsonResponse(200, productsMock.responses[0].body);
    }
    if (method === "POST" && url.pathname === "/api/compare") {
      const code = (body as { conditionChanges: { etfProductCode: string } }).conditionChanges.etfProductCode;
      return jsonResponse(200, code === "133690" ? nasdaqComparison() : etfCompareMock.responses[0].body);
    }
    if (method === "POST" && url.pathname === "/api/plan/apply") return applyResponse();
    return jsonResponse(200, { messages: [], hasPending: false });
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls };
}

function renderResult() {
  render(
    <MemoryRouter initialEntries={[RESULT_PATH]}>
      <NavigateBridge />
      <Routes>
        <Route path={RESULT_PATH} element={<ResultPage />} />
        <Route path={INPUT_PATH} element={<div>입력화면</div>} />
        <Route path={LOGIN_PATH} element={<div>로그인화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

/** 세 종목 카드가 뜰 때까지 기다린다(열기 단계는 없다). */
async function openCards() {
  const section = await screen.findByRole("region", { name: "ETF 종목 비교" });
  await within(section).findByRole("button", { name: NASDAQ });
  return section;
}

describe("결과 화면 ETF 종목 3안 (#156, C12a-9-3)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("카드를 열어 보기만 해서는 내 계획이 바뀌지 않는다", async () => {
    const { calls } = stubServer();
    renderResult();

    const section = await openCards();

    expect(within(section).getByRole("button", { name: SP500 })).toBeTruthy();
    expect(calls.some((call) => call.path === "/api/plan/apply")).toBe(false);
    expect(screen.getByText(new RegExp(`기준 종목 ${SP500}`))).toBeTruthy();
  });

  it("카드를 누르면 확인창 없이 바로 P-6으로 종목만 보내고, 화면의 ETF 기준 값이 새 종목으로 바뀐다", async () => {
    const { calls } = stubServer();
    renderResult();

    const section = await openCards();
    fireEvent.click(within(section).getByRole("button", { name: NASDAQ }));

    await waitFor(() => {
      expect(calls.find((call) => call.path === "/api/plan/apply")?.body).toEqual({
        path: "COMPARE",
        expectedRevision: 12,
        conditionChanges: { etfProductCode: "133690" },
      });
    });
    expect(screen.queryByRole("dialog")).toBeNull();

    // 적용 뒤 계획을 다시 읽어 화면 전체가 새 종목 기준이 된다
    await waitFor(() => {
      expect(screen.getByLabelText("계산 가정").textContent).toContain(NASDAQ);
    });
    expect(screen.getByText(new RegExp(`기준 종목 ${NASDAQ}`))).toBeTruthy();
    await waitFor(() => {
      const next = within(screen.getByLabelText("ETF 종목 비교")).getByRole("button", { name: NASDAQ });
      expect((next as HTMLButtonElement).disabled).toBe(true);
    });
    expect(screen.getByRole("button", { name: "되돌리기" })).toBeTruthy();
  });

  it("적용에 실패하면 확인창 대신 안내가 보이고, 카드는 그대로 열려 있어 다시 누를 수 있다", async () => {
    stubServer(() =>
      jsonResponse(500, { type: "about:blank", status: 500, code: "INTERNAL_ERROR", errors: [] }),
    );
    renderResult();

    const section = await openCards();
    fireEvent.click(within(section).getByRole("button", { name: NASDAQ }));

    expect(await screen.findByText("적용하지 못했어요")).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect((within(section).getByRole("button", { name: NASDAQ }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.queryByText("종목을 바꿨어요. 이전 종목과 비교해 보세요")).toBeNull();
  });
});
