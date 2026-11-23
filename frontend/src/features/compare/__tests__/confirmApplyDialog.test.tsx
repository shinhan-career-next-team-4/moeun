// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import comparisonMock from "../../../mocks/chat/get-comparison.200.json";
import etfCompareMock from "../../../mocks/chat/post-compare.200-etf.json";
import { ConfirmApplyDialog } from "../ConfirmApplyDialog";
import type { ComparisonView } from "../types";

const view = comparisonMock.responses[0].body as unknown as ComparisonView;
const etfView = etfCompareMock.responses[0].body as unknown as ComparisonView;

function renderDialog(props: Partial<Parameters<typeof ConfirmApplyDialog>[0]> = {}) {
  const onCancel = vi.fn();
  const onConfirm = vi.fn();
  render(<ConfirmApplyDialog comparison={view} onCancel={onCancel} onConfirm={onConfirm} {...props} />);
  return { onCancel, onConfirm };
}

describe("적용 확인창 (#68, FR-PLAN-01·02, C12a-9-2)", () => {
  afterEach(cleanup);

  it("ETF 종목을 바꾸면 두 계산의 종목 이름으로 한 줄을 보여 준다(#156)", () => {
    renderDialog({ comparison: etfView });

    const dialog = screen.getByRole("dialog", { name: "바꾼 조건을 내 계획에 적용할까요?" });
    const row = within(dialog).getByText("ETF 종목").closest("li") as HTMLElement;
    expect(row.textContent).toContain("S&P500 추종 ETF → 코스피200 추종 ETF");
  });

  it("바뀌는 조건을 '지금 값 → 바꿀 값'으로 서버 display 그대로 보여 준다", () => {
    renderDialog();

    const dialog = screen.getByRole("dialog", { name: "바꾼 조건을 내 계획에 적용할까요?" });
    const row = within(dialog).getByText("월 저축액").closest("li") as HTMLElement;
    expect(row.textContent).toContain("약 30만원 → 약 50만원");
    // 바꾸지 않은 조건은 보여 주지 않는다
    expect(within(dialog).queryByText("목표 기간")).toBeNull();
  });

  it("변경 목록과 안내를 창 설명으로 연결하고 열리면 취소에 포커스를 둔다", () => {
    renderDialog();

    const dialog = screen.getByRole("dialog");
    const describedBy = dialog.getAttribute("aria-describedby") ?? "";
    expect(describedBy.split(" ").length).toBe(2);
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "취소" }));
  });

  it("Esc를 누르면 onCancel을 부르지만 적용 중에는 부르지 않는다", () => {
    const { onCancel } = renderDialog();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(1);
    cleanup();

    const applying = renderDialog({ applying: true });
    fireEvent.keyDown(window, { key: "Escape" });
    expect(applying.onCancel).not.toHaveBeenCalled();
  });

  it("취소를 누르면 onCancel만 부른다", () => {
    const { onCancel, onConfirm } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("적용하기를 누르면 onConfirm을 부른다", () => {
    const { onConfirm } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "적용하기" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it("적용 중에는 두 버튼을 다시 누를 수 없다(중복 클릭)", () => {
    const { onConfirm, onCancel } = renderDialog({ applying: true });

    fireEvent.click(screen.getByRole("button", { name: "적용하기" }));
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).not.toHaveBeenCalled();
  });

  it("실패 안내가 있으면 창 안에 보여 준다", () => {
    renderDialog({ error: "적용하지 못했어요" });

    expect(within(screen.getByRole("dialog")).getByRole("alert").textContent).toBe("적용하지 못했어요");
  });
});
