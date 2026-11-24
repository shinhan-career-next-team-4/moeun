// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ProjectionUnitToggle } from "../ProjectionUnitToggle";

// 월별 / 연별 토글(#298). 3구간 카드 아래 안내 문구와 같은 줄에 놓인다.

describe("월별/연별 토글 ProjectionUnitToggle (#298)", () => {
  afterEach(cleanup);

  it("'그래프 단위' 라디오 그룹에 월별·연별 두 개가 있고 고른 것만 체크된다", () => {
    render(<ProjectionUnitToggle unit="monthly" onChange={vi.fn()} />);

    expect(screen.getByRole("radiogroup", { name: "그래프 단위" })).toBeTruthy();
    expect(screen.getByRole("radio", { name: "월별" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByRole("radio", { name: "연별" }).getAttribute("aria-checked")).toBe("false");
  });

  it("연별을 누르면 onChange('yearly')를 부른다", () => {
    const onChange = vi.fn();
    render(<ProjectionUnitToggle unit="monthly" onChange={onChange} />);

    fireEvent.click(screen.getByRole("radio", { name: "연별" }));

    expect(onChange).toHaveBeenCalledWith("yearly");
  });
});
