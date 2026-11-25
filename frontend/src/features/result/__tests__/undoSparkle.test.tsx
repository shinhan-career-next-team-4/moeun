// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { PlanConditionBar } from "../PlanConditionBar";
import type { ResultPlanView } from "../types";

// '직전 계획으로 되돌리기' 버튼은 계획이 바뀐 직후(적용 등) 약 3초 반짝인다(#360, #402).
// 버튼은 처음 바뀐 뒤에야 나타나므로 변화는 늘 그려지는 조건 바가 감지한다.

function view(revision: number, undoAvailable: boolean) {
  return { ...(planMock.responses[0].body as Record<string, unknown>), revision, undoAvailable } as unknown as ResultPlanView;
}

const button = () => screen.getByRole("button", { name: "직전 계획으로 되돌리기" });

describe("되돌리기 버튼 반짝임", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  function mount(initial: ResultPlanView) {
    const result = render(
      <MemoryRouter>
        <PlanConditionBar view={initial} />
      </MemoryRouter>,
    );
    const update = (next: ResultPlanView) =>
      act(() =>
        result.rerender(
          <MemoryRouter>
            <PlanConditionBar view={next} />
          </MemoryRouter>,
        ),
      );
    return { update };
  }

  it("처음 그려질 때는 반짝이지 않는다", () => {
    mount(view(5, true));

    expect(button().className).not.toContain("undo-sparkle");
  });

  it("버튼이 없던 계획에서 적용으로 revision이 오르며 버튼이 나타나는 순간 반짝인다", () => {
    const { update } = mount(view(5, false));
    expect(screen.queryByRole("button", { name: "직전 계획으로 되돌리기" })).toBeNull();

    update(view(6, true));

    expect(button().className).toContain("undo-sparkle");
  });

  it("약 3초 뒤 멈추고, 다음 변경에서는 다시 반짝인다", () => {
    const { update } = mount(view(5, false));
    update(view(6, true));
    expect(button().className).toContain("undo-sparkle");

    act(() => {
      vi.advanceTimersByTime(2900);
    });
    expect(button().className).toContain("undo-sparkle");
    act(() => {
      vi.advanceTimersByTime(400);
    });
    expect(button().className).not.toContain("undo-sparkle");

    update(view(7, true));
    expect(button().className).toContain("undo-sparkle");
  });

  it("revision이 그대로면 반짝이지 않는다", () => {
    const { update } = mount(view(5, true));

    update(view(5, true));

    expect(button().className).not.toContain("undo-sparkle");
  });
});
