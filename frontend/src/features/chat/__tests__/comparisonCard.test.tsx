// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ComparisonCard } from "../ComparisonCard";
import type { ComparisonView } from "../types";
import okMock from "../../../mocks/chat/get-comparison.200.json";
import goalChangedMock from "../../../mocks/chat/get-comparison.200-goal-changed.json";
import asymmetricExceededMock from "../../../mocks/chat/get-comparison.200-asymmetric-exceeded.json";
import changedExceededMock from "../../../mocks/chat/get-comparison.200-changed-exceeded.json";

afterEach(cleanup);

describe("ComparisonCard", () => {
  it("일반 비교는 4행 조건과 양쪽 계산 결과·차이를 표시한다", () => {
    const view = okMock.responses[0].body as ComparisonView;
    render(<ComparisonCard comparison={view} onApply={vi.fn()} />);

    expect(screen.queryByTestId("comparison-condition-goalAmount")).toBeNull();
    const monthlyRow = screen.getByTestId("comparison-condition-monthlySavingAmount");
    expect(monthlyRow.textContent).toContain("약 30만원");
    expect(monthlyRow.textContent).toContain("약 50만원");
    expect(screen.getByTestId("comparison-current-calculated").textContent).toContain("약 1,712만원");
    expect(screen.getByTestId("comparison-changed-calculated").textContent).toContain("약 2,501만원");
    expect(screen.getByTestId("comparison-diff-delta").textContent).toContain("약 789만원 증가");
    expect(screen.queryByTestId("comparison-goal-changed-note")).toBeNull();
    expect(screen.queryByTestId("comparison-changed-exceeded-note")).toBeNull();
    expect((screen.getByTestId("comparison-apply") as HTMLButtonElement).disabled).toBe(false);
  });

  it("목표 금액이 바뀌면 5행 조건과 쪽별 목표 금액·주의 문구를 표시한다", () => {
    const view = goalChangedMock.responses[0].body as ComparisonView;
    render(<ComparisonCard comparison={view} onApply={vi.fn()} />);

    expect(view.display["goalAmount"]).toBeUndefined();
    const goalRow = screen.getByTestId("comparison-condition-goalAmount");
    expect(goalRow.textContent).toContain("목표 금액");
    expect(goalRow.textContent).toContain("약 2,000만원");
    expect(goalRow.textContent).toContain("약 1,650만원");
    expect(screen.getByTestId("comparison-goal-changed-note").textContent).toContain(
      "목표 금액이 약 2,000만원에서 약 1,650만원으로 바뀌어, 달성 여부는 각각 자기 목표 기준이에요.",
    );
  });

  it("현재 조건만 초과인 비대칭 비교는 현재 칸에 초과 사실, 바꾼 칸에 계산 결과를 나란히 표시한다", () => {
    const view = asymmetricExceededMock.responses[0].body as ComparisonView;
    render(<ComparisonCard comparison={view} onApply={vi.fn()} />);

    expect(screen.getByTestId("comparison-current-exceeded").textContent).toContain(
      "가진 자산 약 2,500만원이 목표 금액 약 2,000만원을 이미 넘었어요",
    );
    expect(screen.getByTestId("comparison-changed-calculated").textContent).toContain("약 3,712만원");
    expect(screen.queryByTestId("comparison-diff-delta")).toBeNull();
    expect(screen.queryByTestId("comparison-changed-exceeded-note")).toBeNull();
    expect((screen.getByTestId("comparison-apply") as HTMLButtonElement).disabled).toBe(false);
  });

  it("바꾼 조건이 초과가 되면 초과 칸과 예고 문구를 띄우고 적용 버튼은 활성 상태를 유지한다", () => {
    const onApply = vi.fn();
    const view = changedExceededMock.responses[0].body as ComparisonView;
    render(<ComparisonCard comparison={view} onApply={onApply} />);

    expect(screen.getByTestId("comparison-current-calculated").textContent).toContain("약 1,712만원");
    expect(screen.getByTestId("comparison-changed-exceeded").textContent).toContain(
      "가진 자산 약 500만원이 목표 금액 약 400만원을 이미 넘었어요",
    );
    expect(screen.getByTestId("comparison-changed-exceeded-note").textContent).toContain(
      "이 조건으로 적용하면 이미 목표를 넘은 상태가 돼요",
    );

    const applyBtn = screen.getByTestId("comparison-apply") as HTMLButtonElement;
    expect(applyBtn.disabled).toBe(false);
    fireEvent.click(applyBtn);
    expect(onApply).toHaveBeenCalledWith(1036);
  });
});
