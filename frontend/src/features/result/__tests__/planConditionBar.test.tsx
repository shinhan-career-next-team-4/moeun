// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { PlanConditionBar } from "../PlanConditionBar";
import type { ResultPlanView } from "../types";

// 결과 화면 조건 바(FR-RES-05). 입력 화면은 헤더 로고 링크로 가므로 조건 바에 '입력 수정' 링크를 두지 않는다(#288).

function renderBar(undoAvailable: boolean) {
  const view = { ...planMock.responses[0].body, undoAvailable } as unknown as ResultPlanView;
  render(
    <MemoryRouter>
      <PlanConditionBar view={view} />
    </MemoryRouter>,
  );
}

describe("결과 화면 조건 바 (#288)", () => {
  afterEach(cleanup);

  it("조건은 보이지만 '입력 수정' 링크는 없다", () => {
    renderBar(false);

    expect(screen.getByLabelText("내 계획 조건").textContent).toContain("내 계획 조건");
    expect(screen.queryByRole("link", { name: "입력 수정" })).toBeNull();
    expect(screen.queryByText("입력 수정")).toBeNull();
  });

  it("되돌릴 직전 계획이 있으면 '직전 계획으로 되돌리기' 버튼은 그대로 보인다", () => {
    renderBar(true);

    expect(screen.getByRole("button", { name: "직전 계획으로 되돌리기" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "입력 수정" })).toBeNull();
  });
});
