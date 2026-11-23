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

// 결과 화면 ETF 종목 바꾸기(#156 2단계, C12a-9-3): 종목 고르기 -> 미리보기 -> 확인창 -> 적용(P-6).
// 적용하면 화면의 ETF 기준 값이 모두 새 종목 값으로 바뀐다(서버가 다시 계산한 계획을 그대로 보여 준다).

const SP500 = "S&P500 추종 ETF";
const NASDAQ = "나스닥100 추종 ETF";

/** 계획 응답에 종목 display 키를 넣는다. 목에는 아직 없다(#156 1단계 목 반영은 별도 PR) */
function planWith(mock: typeof planMock, productName: string) {
  const body = structuredClone(mock.responses[0].body) as { display: Record<string, string> };
  body.display["assumptions.etfProductName"] = productName;
  body.display["assumptions.etfGainTaxRatePct"] = productName === NASDAQ ? "15.4%" : "15.4%";
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

function stubServer() {
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
    if (method === "POST" && url.pathname === "/api/compare") return jsonResponse(200, nasdaqComparison());
    if (method === "POST" && url.pathname === "/api/plan/apply") return jsonResponse(200, { revision: 13 });
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

/** 나스닥을 골라 미리보기까지. */
async function previewNasdaq() {
  const section = await screen.findByRole("region", { name: "ETF 종목 비교" });
  fireEvent.click(within(section).getByRole("button", { name: "다른 ETF 종목으로 비교해 보기" }));
  fireEvent.click(await within(section).findByRole("radio", { name: NASDAQ }));
  await within(section).findByRole("rowheader", { name: "ETF 종목" });
  return section;
}

describe("결과 화면 ETF 종목 바꾸기 (#156, C12a-9-3)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("종목을 고르면 미리보기만 하고 내 계획은 그대로다", async () => {
    const { calls } = stubServer();
    renderResult();

    await previewNasdaq();

    expect(calls.filter((call) => call.path === "/api/compare")).toHaveLength(1);
    expect(calls.some((call) => call.path === "/api/plan/apply")).toBe(false);
    // 미리보기 중에도 3구간 머리글은 내 계획의 종목이다
    expect(screen.getByText(new RegExp(`기준 종목 ${SP500.replace("&", "&")}`))).toBeTruthy();
  });

  it("적용하면 확인창을 거쳐 P-6으로 종목만 보내고, 화면의 ETF 기준 값이 새 종목으로 바뀐다", async () => {
    const { calls } = stubServer();
    renderResult();

    const section = await previewNasdaq();
    fireEvent.click(within(section).getByRole("button", { name: "이 종목으로 바꾸기" }));

    const dialog = screen.getByRole("dialog", { name: "바꾼 조건을 내 계획에 적용할까요?" });
    expect(within(dialog).getByText("ETF 종목").closest("li")?.textContent).toContain(`${SP500} → ${NASDAQ}`);
    fireEvent.click(within(dialog).getByRole("button", { name: "적용하기" }));

    await waitFor(() => {
      expect(calls.find((call) => call.path === "/api/plan/apply")?.body).toEqual({
        path: "COMPARE",
        expectedRevision: 12,
        conditionChanges: { etfProductCode: "133690" },
      });
    });

    // 적용 뒤 계획을 다시 읽어 화면 전체가 새 종목 기준이 된다
    await waitFor(() => {
      expect(screen.getByLabelText("계산 가정").textContent).toContain(NASDAQ);
    });
    expect(screen.getByText(new RegExp(`기준 종목 ${NASDAQ}`))).toBeTruthy();
    expect(screen.getByLabelText("ETF 종목 비교").textContent).toContain(`지금 종목: ${NASDAQ}`);
    expect(screen.getByRole("button", { name: "되돌리기" })).toBeTruthy();
  });
});
