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
    expect(screen.getByText(/실제 수령액이나 수익을 보장하지 않습니다/)).toBeTruthy();
  });

  // 와이어프레임 03 반영(#136). 숫자는 모두 서버 display 문자열을 그대로 쓴다.
  describe("와이어프레임 반영 (#136)", () => {
    function withoutDisplayKeys(keys: string[]) {
      const body = structuredClone(planMock.responses[0].body) as { display: Record<string, string> };
      for (const key of keys) delete body.display[key];
      return body;
    }

    it("(W-03) 요약 카드에 원금·이자 분해 한 줄이 display 문자열 그대로 보인다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      const summary = await screen.findByLabelText("결과 요약");
      expect(summary.textContent).toContain("원금 약 1,580만원 + 이자·수익 약 132만원(세후)");
    });

    it("(W-03 예외) principal·afterTaxGain display 키가 없으면 그 줄만 숨긴다", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(jsonResponse(200, withoutDisplayKeys(["AVG.principal", "AVG.afterTaxGain"]))),
      );
      renderResult();

      const summary = await screen.findByLabelText("결과 요약");
      expect(summary.textContent).toContain("약 1,712만원");
      expect(summary.textContent).not.toContain("원금");
    });

    it("(W-04) 그래프에 범위 밴드와 범례 세 개가 보인다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      const chart = await screen.findByLabelText("예상 자산 추이 그래프");
      expect(screen.getByText("평균")).toBeTruthy();
      expect(screen.getByText("좋았던 때~나빴던 때 범위")).toBeTruthy();
      expect(screen.getByText("목표선")).toBeTruthy();
      // 밴드는 닫힌 path(Z)로 그린다
      const filled = [...chart.querySelectorAll("path")].filter((node) => node.getAttribute("d")?.endsWith("Z"));
      expect(filled.length).toBe(1);
    });

    it("(W-04 예외) GOOD·BAD monthlyPoints가 없으면 밴드 없이 평균선과 목표선만 그린다", async () => {
      const body = structuredClone(planMock.responses[0].body) as {
        result: { scenarios: { scenario: string; monthlyPoints: unknown[] }[] };
      };
      for (const scenario of body.result.scenarios) {
        if (scenario.scenario !== "AVG") scenario.monthlyPoints = [];
      }
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, body)));
      renderResult();

      const chart = await screen.findByLabelText("예상 자산 추이 그래프");
      expect([...chart.querySelectorAll("path")].filter((n) => n.getAttribute("d")?.endsWith("Z")).length).toBe(0);
      expect(screen.queryByText("좋았던 때~나빴던 때 범위")).toBeNull();
      expect(screen.getByText("평균")).toBeTruthy();
    });

    it("(W-06) 3구간 머리글과 구간 산정 설명이 보인다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      expect(await screen.findByText("ETF가 포함된 결과는 시기에 따라 달라요")).toBeTruthy();
      // assumptions.scenarioMethod = ROLLING_DISTRIBUTION에 대응하는 짧은 문구표 문장
      expect(screen.getByText(/기준 지수 S&P500 · 과거 10년 수익률로 1만 가지 경우를 계산해 세 구간/)).toBeTruthy();
    });

    it("(W-06 예외) scenarioMethod가 null이면 설명 줄만 숨긴다", async () => {
      const body = structuredClone(planMock.responses[0].body) as {
        result: { assumptions: { scenarioMethod: string | null } };
      };
      body.result.assumptions.scenarioMethod = null;
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, body)));
      renderResult();

      expect(await screen.findByText("ETF가 포함된 결과는 시기에 따라 달라요")).toBeTruthy();
      expect(screen.queryByText(/기준 지수 S&P500/)).toBeNull();
    });

    it("(W-26) 가로축 양 끝은 display 값이고 중간 눈금은 1년 간격이다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      const chart = await screen.findByLabelText("예상 자산 추이 그래프");
      const labels = [...chart.querySelectorAll("text")].map((node) => node.textContent);
      expect(labels).toContain("2026년 11월");
      expect(labels).toContain("2029년 11월");
      expect(labels).toContain("2027.11");
      expect(labels).toContain("2028.11");
    });

    it("(W-19) 면책 문구에 다섯 가지가 모두 들어 있다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      await screen.findByLabelText("내 계획 조건");
      const text = document.body.textContent ?? "";
      for (const phrase of [
        "모의 계산",
        "실제 수령액이나 수익을 보장하지 않습니다",
        "특정 금융상품의 가입을 권유하지 않으며",
        "환율 변동은 반영하지 않았습니다",
        "본인의 판단과 책임",
      ]) {
        expect(text, phrase).toContain(phrase);
      }
    });
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
