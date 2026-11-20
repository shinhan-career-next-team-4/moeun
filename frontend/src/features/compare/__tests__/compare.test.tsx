// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import comparisonMock from "../../../mocks/chat/get-comparison.200.json";
import notApplicableMock from "../../../mocks/chat/get-comparison.200-not-applicable.json";
import { CompareSection } from "../CompareSection";
import { requestComparison } from "../requestComparison";
import type { ComparisonView } from "../types";

const view = comparisonMock.responses[0].body as unknown as ComparisonView;
const notApplicable = notApplicableMock.responses[0].body as unknown as ComparisonView;

const currentDisplay: Record<string, string> = {
  goalAmount: "약 2,000만원",
  "conditions.monthlySavingAmount": "약 30만원",
  "conditions.targetPeriodMonths": "3년 0개월",
  "conditions.savingsRateInputPct": "연 3.12%",
};

function renderSection(props: Partial<Parameters<typeof CompareSection>[0]> = {}) {
  const onCompare = props.onCompare ?? vi.fn();
  const onReset = props.onReset ?? vi.fn();
  render(
    <CompareSection
      currentDisplay={currentDisplay}
      comparison={props.comparison ?? null}
      loading={props.loading}
      error={props.error}
      onCompare={onCompare}
      onReset={onReset}
    />,
  );
  return { onCompare, onReset };
}

function field(label: string) {
  return screen.getByRole("textbox", { name: label });
}

function pick(label: string) {
  fireEvent.click(screen.getByRole("checkbox", { name: label }));
}

describe("비교 영역 (#65, FR-CMP-01~03)", () => {
  afterEach(cleanup);

  it("머리글 두 줄과 조건 고르기가 보인다", () => {
    renderSection();

    expect(screen.getByText("조건을 바꿔 비교해 보세요")).toBeTruthy();
    expect(screen.getByText(/적용하기 전까지 내 계획은 바뀌지 않아요/)).toBeTruthy();
    expect(screen.getByRole("checkbox", { name: "월 저축액" })).toBeTruthy();
    expect(screen.getByRole("checkbox", { name: "저축 비중" })).toBeTruthy();
    expect(screen.getByRole("checkbox", { name: "목표 금액" })).toBeTruthy();
  });

  it("조건 둘을 골라 값을 넣고 비교하면 바꾼 조건을 원 단위로 넘긴다", () => {
    const { onCompare } = renderSection();

    pick("월 저축액");
    pick("목표 기간");
    fireEvent.change(field("월 저축액"), { target: { value: "150" } });
    fireEvent.change(field("목표 기간"), { target: { value: "48" } });
    fireEvent.click(screen.getByRole("button", { name: "비교하기" }));

    expect(onCompare).toHaveBeenCalledWith({ monthlySavingAmount: 1500000, targetPeriodMonths: 48 });
  });

  it("고른 조건 아래에 현재 값을 보여 준다", () => {
    renderSection();

    pick("월 저축액");
    expect(screen.getByText("현재 약 30만원")).toBeTruthy();
  });

  it("비중을 고르면 두 칸과 안내가 보이고 합이 100이 아니면 비교하지 않는다", () => {
    const { onCompare } = renderSection();

    pick("저축 비중");
    expect(screen.getByText(/합계는 100%가 되어야 해요/)).toBeTruthy();
    fireEvent.change(field("적금"), { target: { value: "60" } });
    fireEvent.change(field("ETF"), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "비교하기" }));

    expect(onCompare).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toContain("100이 되게");

    fireEvent.change(field("ETF"), { target: { value: "40" } });
    fireEvent.click(screen.getByRole("button", { name: "비교하기" }));
    expect(onCompare).toHaveBeenCalledWith({ savingsRatioPct: 60 });
  });

  it("비교 결과가 오면 현재 조건과 바꾼 조건을 display 문자열 그대로 나란히 보여 준다", () => {
    renderSection({ comparison: view });

    expect(screen.getByText("약 1,712만원")).toBeTruthy();
    expect(screen.getByText("약 2,501만원")).toBeTruthy();
    expect(screen.getByText("약 789만원 증가")).toBeTruthy();
    expect(screen.getByText("-약 288만원 미달")).toBeTruthy();
    expect(screen.getByText("+약 501만원 달성")).toBeTruthy();
    expect(screen.getAllByText("미달에서 달성으로 바뀌어요").length).toBeGreaterThan(0);
    expect(screen.getByText("2029년 2월")).toBeTruthy();
    expect(screen.getByText("총 납입 원금")).toBeTruthy();
  });

  it("applicable이 false면 표 대신 안내를 보여 준다", () => {
    renderSection({ comparison: notApplicable });

    expect(screen.getByText("지금 기준으로는 비교할 수 없는 값이에요")).toBeTruthy();
    expect(screen.queryByText("총 납입 원금")).toBeNull();
  });

  it("원래대로를 누르면 고른 조건과 입력이 비고 저장은 일어나지 않는다", () => {
    const { onReset, onCompare } = renderSection();

    pick("월 저축액");
    fireEvent.change(field("월 저축액"), { target: { value: "150" } });
    fireEvent.click(screen.getByRole("button", { name: "원래대로" }));

    expect(onReset).toHaveBeenCalledTimes(1);
    expect(onCompare).not.toHaveBeenCalled();
    expect(screen.queryByRole("textbox", { name: "월 저축액" })).toBeNull();
    expect((screen.getByRole("checkbox", { name: "월 저축액" }) as HTMLInputElement).checked).toBe(false);
  });

  it("조건을 고르지 않으면 비교하기를 누를 수 없다", () => {
    renderSection();

    expect((screen.getByRole("button", { name: "비교하기" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("적용 버튼은 확인 흐름이 붙기 전까지 비활성이다(D-046)", () => {
    renderSection();

    const apply = screen.getByRole("button", { name: "바꾼 조건을 내 계획으로 적용" }) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);
    expect(apply.title).toBe("적용은 확인 흐름이 붙은 뒤에 쓸 수 있어요");
  });

  it("비교하지 못했으면 안내를 보여 준다", () => {
    renderSection({ error: "비교는 조건 변경 비교 API가 붙은 뒤에 쓸 수 있어요" });

    expect(screen.getByRole("status").textContent).toContain("조건 변경 비교 API");
  });
});

describe("requestComparison (#65, #64 대기)", () => {
  it("목 모드에서는 비교 결과를 돌려준다", async () => {
    const request = vi.fn().mockResolvedValue(view);

    const result = await requestComparison({ mode: "mock", request }, { monthlySavingAmount: 1500000 });

    expect(result).toEqual({ ok: true, view });
    expect(request).toHaveBeenCalledWith("GET", "/api/chat/messages/1031/comparison");
  });

  it("실서버 모드에서는 비교하지 않고 안내를 돌려준다", async () => {
    const request = vi.fn();

    const result = await requestComparison({ mode: "live", request }, { monthlySavingAmount: 1500000 });

    expect(result).toEqual({ ok: false, message: "비교는 조건 변경 비교 API가 붙은 뒤에 쓸 수 있어요" });
    expect(request).not.toHaveBeenCalled();
  });

  it("바꾼 조건이 없으면 요청하지 않는다", async () => {
    const request = vi.fn();

    const result = await requestComparison({ mode: "mock", request }, {});

    expect(result).toEqual({ ok: false, message: "바꿀 조건을 하나 이상 골라 주세요" });
    expect(request).not.toHaveBeenCalled();
  });

  it("요청이 실패하면 실패 안내를 돌려준다", async () => {
    const request = vi.fn().mockRejectedValue(new Error("boom"));

    const result = await requestComparison({ mode: "mock", request }, { monthlySavingAmount: 1500000 });

    expect(result).toEqual({ ok: false, message: "비교 결과를 불러오지 못했어요" });
  });
});
