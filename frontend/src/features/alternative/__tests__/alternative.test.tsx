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
    expect(screen.getAllByText("아직 계산된 값이 없어요")).toHaveLength(3);
  });

  it("달성이면 대안 영역이 없다", () => {
    render(<AlternativeSection isAchieved={true} />);

    expect(screen.queryByLabelText("대안")).toBeNull();
  });

  it("달성이 어려운 대안은 그 안내만 보이고 비교·적용 버튼은 막힌다(#343)", () => {
    render(
      <AlternativeSection
        isAchieved={false}
        applyEnabled
        onCompare={vi.fn()}
        onApply={vi.fn()}
        values={{ savingsRatioPct: { unreachable: true } }}
      />,
    );

    expect(screen.getByText("ETF 100%로 바꿔도 목표 달성이 어려워요")).toBeTruthy();
    const cards = screen.getAllByRole("listitem");
    const ratioCard = cards[2];
    expect((ratioCard.querySelectorAll("button")[0] as HTMLButtonElement).disabled).toBe(true);
    expect((ratioCard.querySelectorAll("button")[1] as HTMLButtonElement).disabled).toBe(true);
  });

  it("기한 카드에는 더 이상 기간 입력칸이 없다(서버가 달성하는 기간을 추천한다, #343)", () => {
    render(<AlternativeSection isAchieved={false} />);

    expect(screen.queryByLabelText("원하는 기한(개월)")).toBeNull();
  });

  it("달성하는 대안은 예상 자산·목표 대비 줄이 초록이고, 아니면 기본색이다(#352)", () => {
    render(
      <AlternativeSection
        isAchieved={false}
        values={{
          monthlySavingAmount: { avgAfterTaxAsset: "약 10,015만원", gapToGoal: "+약 15만원 달성", achieved: true },
          targetPeriodMonths: { avgAfterTaxAsset: "약 9,000만원", gapToGoal: "-약 1,000만원 미달", achieved: false },
        }}
      />,
    );

    expect(screen.getByText("약 10,015만원").className).toContain("text-success");
    expect(screen.getByText("+약 15만원 달성").className).toContain("text-success");
    expect(screen.getByText("약 9,000만원").className).not.toContain("text-success");
    expect(screen.getByText("-약 1,000만원 미달").className).not.toContain("text-success");
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
    expect(screen.getAllByText("아직 계산된 값이 없어요")).toHaveLength(2);
  });
  it("서버가 변동이 커진다고 알려 준 카드에만 변동 경고가 붙는다(W-10)", () => {
    render(
      <AlternativeSection
        isAchieved={false}
        values={{
          savingsRatioPct: { badAfterTaxAsset: "약 4,610만원 · 미달", volatilityWarning: true },
          monthlySavingAmount: { badAfterTaxAsset: "약 4,856만원 · 미달" },
        }}
      />,
    );

    expect(screen.getAllByText("⚠ 변동 커짐")).toHaveLength(1);
  });

  it("비교하기를 누르면 그 대안의 조건을 넘긴다(FR-ALT-04)", () => {
    const onCompare = vi.fn();
    const values = { avgAfterTaxAsset: "약 5,017만원", changes: { monthlySavingAmount: 12100000 } };
    render(<AlternativeSection isAchieved={false} values={{ monthlySavingAmount: values }} onCompare={onCompare} />);

    const buttons = screen.getAllByRole("button", { name: "비교하기" }) as HTMLButtonElement[];
    expect(buttons.filter((button) => !button.disabled)).toHaveLength(1);
    fireEvent.click(buttons.filter((button) => !button.disabled)[0]);

    expect(onCompare).toHaveBeenCalledWith(values);
  });

  it("값이 없거나 부모가 막으면 내 계획으로 적용을 누를 수 없다", () => {
    render(<AlternativeSection isAchieved={false} onApply={vi.fn()} applyEnabled />);

    const apply = screen.getAllByRole("button", { name: "내 계획으로 적용" }) as HTMLButtonElement[];
    expect(apply).toHaveLength(3);
    expect(apply.every((button) => button.disabled)).toBe(true);
  });

  it("값이 있고 부모가 허용하면 내 계획으로 적용을 눌러 그 대안을 넘긴다(#258, FR-ALT-04)", () => {
    const onApply = vi.fn();
    const values = { changedCondition: "약 30만원 → 약 33만원", changes: { monthlySavingAmount: 330000 } };
    const { rerender } = render(
      <AlternativeSection isAchieved={false} values={{ monthlySavingAmount: values }} onApply={onApply} applyEnabled />,
    );

    const [apply] = screen.getAllByRole("button", { name: "내 계획으로 적용" }) as HTMLButtonElement[];
    expect(apply.disabled).toBe(false);
    fireEvent.click(apply);
    expect(onApply).toHaveBeenCalledWith(values);

    rerender(
      <AlternativeSection isAchieved={false} values={{ monthlySavingAmount: values }} onApply={onApply} applyEnabled={false} />,
    );
    expect((screen.getAllByRole("button", { name: "내 계획으로 적용" })[0] as HTMLButtonElement).disabled).toBe(true);
  });

  it("값이 없는 카드는 비교하기를 누를 수 없다", () => {
    render(<AlternativeSection isAchieved={false} onCompare={vi.fn()} />);

    const buttons = screen.getAllByRole("button", { name: "비교하기" }) as HTMLButtonElement[];
    expect(buttons.every((button) => button.disabled)).toBe(true);
  });
});
