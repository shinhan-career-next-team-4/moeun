// @vitest-environment jsdom
// #59: 결과가 나온 모든 상태에서 맨 아래 면책 문구가 있고, 3구간 카드 아래에 과거 수익률 고지가 있다(FR-RES-07, CP-05, D-066).
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import { RESULT_PATH } from "../../../app/routes";
import afterApplyMock from "../../../mocks/plan/get-plan.200-after-apply.json";
import afterUndoMock from "../../../mocks/plan/get-plan.200-after-undo.json";
import etf0Mock from "../../../mocks/plan/get-plan.200-etf0.json";
import exceededMock from "../../../mocks/plan/get-plan.200-exceeded.json";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { COPY } from "../../../shared/copy/copy";
import { ResultPage } from "../ResultPage";

const DISCLAIMER_PHRASE = "실제 수령액이나 수익을 보장하지 않습니다";
const SCENARIO_NOTICE_PHRASE = "미래를 예측한 값이 아니에요";

type State = { name: string; body: unknown; hasScenarios: boolean };

const STATES: State[] = [
  { name: "정상(3구간)", body: planMock.responses[0].body, hasScenarios: true },
  { name: "ETF 0%(평균 1개)", body: etf0Mock.responses[0].body, hasScenarios: true },
  { name: "이미 목표 초과", body: exceededMock.responses[0].body, hasScenarios: false },
  { name: "대화 적용 뒤", body: afterApplyMock.responses[0].body, hasScenarios: true },
  { name: "되돌린 뒤", body: afterUndoMock.responses[0].body, hasScenarios: true },
];

describe("결과 화면의 면책·과거 수익률 고지 (#59)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  async function renderResult(body: unknown) {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }),
      ),
    );
    render(
      <MemoryRouter initialEntries={[RESULT_PATH]}>
        <Routes>
          <Route path={RESULT_PATH} element={<ResultPage />} />
        </Routes>
      </MemoryRouter>,
    );
    await screen.findByLabelText("내 계획 조건");
  }

  it("COPY 상수가 면책·과거 수익률 고지 문장을 담고 있다", () => {
    expect(COPY.disclaimer).toContain(DISCLAIMER_PHRASE);
    expect(COPY["result.scenarioNotice"]).toContain(SCENARIO_NOTICE_PHRASE);
  });

  describe.each(STATES)("$name", ({ body, hasScenarios }) => {
    it("면책 문구가 본문 맨 아래 문단으로 한 번 있고, footer 영역은 없다", async () => {
      await renderResult(body);

      expect(document.querySelectorAll("footer")).toHaveLength(0);
      const disclaimers = screen.getAllByTestId("result-disclaimer");
      expect(disclaimers).toHaveLength(1);
      const disclaimer = disclaimers[0];
      expect(disclaimer.textContent).toContain(DISCLAIMER_PHRASE);
      // 다른 모든 구역보다 뒤에 있다
      for (const section of Array.from(document.querySelectorAll("section"))) {
        expect(section.compareDocumentPosition(disclaimer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      }
    });

    it(
      hasScenarios ? "과거 수익률 고지가 비교 카드 아래에 있다" : "카드가 없으면 과거 수익률 고지도 없다",
      async () => {
        await renderResult(body);

        const notice = screen.queryByText(new RegExp(SCENARIO_NOTICE_PHRASE));
        if (!hasScenarios) {
          expect(notice).toBeNull();
          expect(screen.queryByLabelText("세 가지 경우 비교")).toBeNull();
          return;
        }
        expect(notice).not.toBeNull();
        const section = screen.getByLabelText("세 가지 경우 비교");
        expect(section.contains(notice)).toBe(true);
        const cards = Array.from(section.querySelectorAll("article"));
        expect(cards.length).toBeGreaterThan(0);
        for (const card of cards) {
          expect(card.compareDocumentPosition(notice as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        }
      },
    );
  });
});
