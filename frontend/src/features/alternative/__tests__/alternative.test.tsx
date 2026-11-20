// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { AlternativeSection } from "../AlternativeSection";

describe("대안 영역 (#67, FR-ALT-01·02)", () => {
  afterEach(cleanup);

  it("미달이면 카드 3개와 머리글이 보이고, 값이 없는 카드는 빈 카드다", () => {
    render(<AlternativeSection isAchieved={false} />);

    expect(screen.getByText("목표에 닿으려면 이렇게 바꿀 수 있어요")).toBeTruthy();
    expect(screen.getByText(/순서는 우열이 아니며, 서비스는 하나를 추천하지 않아요/)).toBeTruthy();
    expect(screen.getByText("월 저축액을 바꾸면")).toBeTruthy();
    expect(screen.getByText("기한을 늘리면")).toBeTruthy();
    expect(screen.getByText("비중을 바꾸면")).toBeTruthy();
    expect(screen.getAllByText("대안 값은 계산 서버에 연결하면 보여 드릴게요")).toHaveLength(3);
  });

  it("달성이면 대안 영역이 없다", () => {
    render(<AlternativeSection isAchieved={true} />);

    expect(screen.queryByLabelText("대안")).toBeNull();
  });

  it("기한 카드에만 기간 입력칸과 안내가 있다(D-020·D-039)", () => {
    render(<AlternativeSection isAchieved={false} onPeriodSubmit={vi.fn()} />);

    expect(screen.getByText("원하는 기한을 적어 주면 그 기간까지의 그래프를 보여 드릴게요")).toBeTruthy();
    expect(screen.getAllByLabelText("원하는 기한(개월)")).toHaveLength(1);
  });

  it("기간을 적고 확인하면 그 기간을 넘겨주고 적은 기한을 알려 준다", () => {
    const onPeriodSubmit = vi.fn();
    render(<AlternativeSection isAchieved={false} onPeriodSubmit={onPeriodSubmit} />);

    fireEvent.change(screen.getByLabelText("원하는 기한(개월)"), { target: { value: "42" } });
    fireEvent.click(screen.getByRole("button", { name: "확인" }));

    expect(onPeriodSubmit).toHaveBeenCalledWith(42);
    expect(screen.getByText(/기한 42개월로 적었어요/)).toBeTruthy();
  });

  it("기간이 비었거나 숫자가 아니면 확인을 누를 수 없다", () => {
    render(<AlternativeSection isAchieved={false} onPeriodSubmit={vi.fn()} />);
    const submit = screen.getByRole("button", { name: "확인" }) as HTMLButtonElement;

    expect(submit.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("원하는 기한(개월)"), { target: { value: "3년" } });
    expect(submit.disabled).toBe(true);
  });

  it("서버가 준 값이 있으면 카드 본문 줄을 display 문자열 그대로 보여 준다", () => {
    render(
      <AlternativeSection
        isAchieved={false}
        values={{
          monthlySavingAmount: {
            changedCondition: "월 120만원 → 121만원",
            change: "+약 1만원 증가",
            avgAfterTaxAsset: "약 5,017만원",
            gapToGoal: "+약 17만원 달성",
            badAfterTaxAsset: "약 4,856만원 · 미달",
          },
        }}
      />,
    );

    expect(screen.getByText("월 120만원 → 121만원")).toBeTruthy();
    expect(screen.getByText("약 5,017만원")).toBeTruthy();
    expect(screen.getByText("+약 17만원 달성")).toBeTruthy();
    expect(screen.getByText("약 4,856만원 · 미달")).toBeTruthy();
    // 값이 온 카드만 채워지고 나머지 둘은 빈 카드다
    expect(screen.getAllByText("대안 값은 계산 서버에 연결하면 보여 드릴게요")).toHaveLength(2);
  });
});
