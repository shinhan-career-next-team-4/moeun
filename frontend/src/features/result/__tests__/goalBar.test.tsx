// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { GoalBar } from "../GoalBar";

describe("목표 대비 가로 막대 (#146, FR-RES-02)", () => {
  afterEach(cleanup);

  it("미달이면 자산만큼 채우고 목표 표시는 오른쪽 끝에 온다", () => {
    render(
      <GoalBar afterTaxAsset={17120000} goalAmount={20000000} goalAmountText="약 2,000만원" isAchieved={false} />,
    );

    expect(screen.getByRole("img", { name: "목표 대비 예상 자산" })).toBeTruthy();
    expect(screen.getByTestId("goal-bar-fill").style.width).toBe("85.6%");
    expect(screen.getByTestId("goal-bar-marker").style.left).toBe("100%");
    expect(screen.getByText("▲ 목표 약 2,000만원")).toBeTruthy();
    expect(screen.getByText("0")).toBeTruthy();
  });

  it("목표를 넘기면 목표 표시가 막대 중간에 오고 채운 부분이 끝까지 간다", () => {
    render(
      <GoalBar afterTaxAsset={25000000} goalAmount={20000000} goalAmountText="약 2,000만원" isAchieved={true} />,
    );

    expect(screen.getByTestId("goal-bar-fill").style.width).toBe("100%");
    expect(screen.getByTestId("goal-bar-marker").style.left).toBe("80%");
  });

  it("목표 금액 display 문자열이 없으면 막대를 그리지 않는다", () => {
    render(<GoalBar afterTaxAsset={17120000} goalAmount={20000000} isAchieved={false} />);

    expect(screen.queryByRole("img", { name: "목표 대비 예상 자산" })).toBeNull();
  });

  it("목표 금액이 0이면 막대를 그리지 않는다", () => {
    render(<GoalBar afterTaxAsset={17120000} goalAmount={0} goalAmountText="약 0원" isAchieved={true} />);

    expect(screen.queryByRole("img", { name: "목표 대비 예상 자산" })).toBeNull();
  });

  it("라벨에 프론트가 만든 숫자 문자열이 없다(서버 display만 쓴다)", () => {
    render(
      <GoalBar afterTaxAsset={17120000} goalAmount={20000000} goalAmountText="약 2,000만원" isAchieved={false} />,
    );

    const text = screen.getByRole("img", { name: "목표 대비 예상 자산" }).textContent ?? "";
    expect(text).toBe("0▲ 목표 약 2,000만원");
  });
});
