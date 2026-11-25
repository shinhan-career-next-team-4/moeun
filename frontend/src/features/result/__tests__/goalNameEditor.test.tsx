// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { planStore } from "../../../api";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { GoalNameEditor } from "../GoalNameEditor";
import type { ResultPlanView } from "../types";

// 조건 바의 목표 이름을 그 자리에서 바꾼다(#341). 저장은 PUT /api/plan이고 종류는 CUSTOM으로 보낸다.

const view = planMock.responses[0].body as unknown as ResultPlanView;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("목표 이름 바로 수정 (#341)", () => {
  beforeEach(() => planStore.clear());
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("이름은 눌러서 고칠 수 있는 단추로 보이고, 누르면 입력칸으로 바뀐다", () => {
    render(<GoalNameEditor view={view} />);

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    expect((screen.getByLabelText("목표 이름") as HTMLInputElement).value).toBe("내 집 마련");
  });

  it("대화로 이름을 바꿔 적용한 계획(CUSTOM + 이름)이면 그 이름을 보인다(#345)", () => {
    const applied = { ...view, plan: { ...view.plan, goalType: "CUSTOM", goalCustomName: "가게 보증금" } } as ResultPlanView;
    render(<GoalNameEditor view={applied} />);

    expect(screen.getByRole("button", { name: "목표 이름 수정: 가게 보증금" }).textContent).toBe("가게 보증금");
  });

  it("새 이름을 저장하면 PUT /api/plan에 CUSTOM과 trim한 이름을 싣고 나머지 조건은 그대로 보낸다", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(jsonResponse(200, view));
    vi.stubGlobal("fetch", fetchSpy);
    render(<GoalNameEditor view={view} />);

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    fireEvent.change(screen.getByLabelText("목표 이름"), { target: { value: "  우리 집 보증금  " } });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));
    const [path, init] = fetchSpy.mock.calls[0];
    expect(path).toBe("/api/plan");
    expect(init.method).toBe("PUT");
    const body = JSON.parse(init.body as string);
    expect(body).toMatchObject({
      goalType: "CUSTOM",
      goalCustomName: "우리 집 보증금",
      goalAmount: view.plan.goalAmount,
      targetPeriodMonths: view.plan.targetPeriodMonths,
      heldAssets: view.plan.heldAssets,
      monthlySavingAmount: view.plan.monthlySavingAmount,
    });
    await waitFor(() => expect(screen.queryByLabelText("목표 이름")).toBeNull());
  });

  it("이름이 비어 있으면 서버에 보내지 않고 필수 안내를 보인다", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    render(<GoalNameEditor view={view} />);

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    fireEvent.change(screen.getByLabelText("목표 이름"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("이름을 바꾸지 않고 저장하면 서버에 보내지 않고 닫는다", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    render(<GoalNameEditor view={view} />);

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    fireEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(screen.queryByLabelText("목표 이름")).toBeNull());
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("취소하거나 Esc를 누르면 저장 없이 닫힌다", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    render(<GoalNameEditor view={view} />);

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.queryByLabelText("목표 이름")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    fireEvent.keyDown(screen.getByLabelText("목표 이름"), { key: "Escape" });
    expect(screen.queryByLabelText("목표 이름")).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("서버 저장이 실패하면 입력칸을 유지하고 실패 안내를 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, { code: "INTERNAL" })));
    render(<GoalNameEditor view={view} />);

    fireEvent.click(screen.getByRole("button", { name: /목표 이름 수정/ }));
    fireEvent.change(screen.getByLabelText("목표 이름"), { target: { value: "새 이름" } });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((screen.getByLabelText("목표 이름") as HTMLInputElement).value).toBe("새 이름");
  });
});
