// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { planStore } from "../../../api";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { UndoPlanButton } from "../UndoPlanButton";

// '직전 계획으로 되돌리기' 버튼은 계획이 바뀌면 한 번 반짝인다(#360).

function body(revision: number) {
  return { ...(planMock.responses[0].body as Record<string, unknown>), revision, undoAvailable: true };
}

const button = () => screen.getByRole("button", { name: "직전 계획으로 되돌리기" });

describe("되돌리기 버튼 반짝임 (#360)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    planStore.clear();
    planStore.acceptPlanView(body(5) as never);
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  function mount() {
    render(
      <MemoryRouter>
        <UndoPlanButton onFeedback={() => {}} />
      </MemoryRouter>,
    );
  }

  it("처음 그려질 때는 반짝이지 않는다", () => {
    mount();

    expect(button().className).not.toContain("undo-sparkle");
  });

  it("계획 revision이 바뀌면 반짝이고, 애니메이션이 끝나면 멈춘다", () => {
    mount();

    act(() => planStore.acceptPlanView(body(6) as never));
    expect(button().className).toContain("undo-sparkle");

    act(() => {
      vi.advanceTimersByTime(1500);
    });
    expect(button().className).not.toContain("undo-sparkle");

    // 다시 바뀌면 또 반짝인다
    act(() => planStore.acceptPlanView(body(7) as never));
    expect(button().className).toContain("undo-sparkle");
  });

  it("같은 revision을 다시 받으면 반짝이지 않는다", () => {
    mount();

    act(() => planStore.acceptPlanView(body(5) as never));
    expect(button().className).not.toContain("undo-sparkle");
  });
});
