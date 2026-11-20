// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import etf0Mock from "../../../mocks/plan/get-plan.200-etf0.json";
import exceededMock from "../../../mocks/plan/get-plan.200-exceeded.json";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { ResultPage } from "../ResultPage";

function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": contentType },
  });
}

describe("ResultPage (#49·#50, C12a-7)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderResult() {
    return render(
      <MemoryRouter initialEntries={[RESULT_PATH]}>
        <Routes>
          <Route path={RESULT_PATH} element={<ResultPage />} />
          <Route path={INPUT_PATH} element={<div>입력화면</div>} />
          <Route path={LOGIN_PATH} element={<div>로그인화면</div>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it("CALCULATED 응답이면 조건 바·결과 요약·3구간·그래프·가정·면책을 모두 표시한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
    renderResult();

    expect(await screen.findByLabelText("내 계획 조건")).toBeTruthy();
    expect(screen.getByLabelText("결과 요약").textContent).toContain("약 1,712만원");
    expect(screen.getByLabelText("세 가지 경우 비교")).toBeTruthy();
    expect(screen.getByText("좋은 경우")).toBeTruthy();
    expect(screen.getByText("보통 경우")).toBeTruthy();
    expect(screen.getByText("나쁜 경우")).toBeTruthy();
    expect(screen.getByRole("img", { name: "예상 자산 추이" })).toBeTruthy();
    expect(screen.getByLabelText("계산 가정").textContent).toContain("15.4%");
    expect(screen.getByText(/미래의 수익을 보장하지 않습니다/)).toBeTruthy();
  });

  it("ALREADY_EXCEEDED면 초과 안내만 표시하고 요약·3구간·그래프를 숨긴다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, exceededMock.responses[0].body)));
    renderResult();

    const banner = await screen.findByLabelText("목표 초과 안내");
    expect(banner.textContent).toContain("가진 자산 약 2,500만원이 목표 금액 약 2,000만원을 이미 넘었어요");
    expect(screen.queryByLabelText("결과 요약")).toBeNull();
    expect(screen.queryByLabelText("세 가지 경우 비교")).toBeNull();
    expect(screen.queryByRole("img", { name: "예상 자산 추이" })).toBeNull();
  });

  it("ETF 0%면 보통 경우 1개 카드만 그린다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, etf0Mock.responses[0].body)));
    renderResult();

    await screen.findByLabelText("세 가지 경우 비교");
    expect(screen.getByText("보통 경우")).toBeTruthy();
    expect(screen.queryByText("좋은 경우")).toBeNull();
    expect(screen.queryByText("나쁜 경우")).toBeNull();
  });

  it("500 오류면 loadFailed와 다시 시도 버튼을 표시하고 재시도로 복구한다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(500, { code: "INTERNAL_ERROR", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(jsonResponse(200, planMock.responses[0].body));
    vi.stubGlobal("fetch", fetchSpy);
    renderResult();

    expect((await screen.findByRole("alert")).textContent).toContain("결과를 불러오지 못했어요");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    await waitFor(() => {
      expect(screen.getByLabelText("결과 요약")).toBeTruthy();
    });
  });
  // 대안 영역 연결(#67, FR-ALT-01)
  it("기준 시나리오가 미달이면 대안 영역이 보이고 달성이면 보이지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
    renderResult();

    expect(await screen.findByLabelText("대안")).toBeTruthy();
    cleanup();

    const achieved = structuredClone(planMock.responses[0].body) as {
      result: { scenarios: { scenario: string; isAchieved: boolean }[] };
    };
    for (const scenario of achieved.result.scenarios) scenario.isAchieved = true;
    planStore.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, achieved)));
    renderResult();

    await screen.findByLabelText("결과 요약");
    expect(screen.queryByLabelText("대안")).toBeNull();
  });
});
