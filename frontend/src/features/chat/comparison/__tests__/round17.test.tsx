// @vitest-environment jsdom
// 재개(P-75, 2라운드 B1): 토스트 폐기와 늦은 NO_PLAN이 React 정리보다 먼저 이어져도 새 계획을 비우지 않는다.
// 잠긴 테스트와 별도 파일이다. 진짜 되돌리기 토스트와 실제 API 래퍼·공유 store를 쓴다.
// 부모가 onDismiss에 맞춰 다시 그리기 전(= effect 정리 전)을 그대로 보려고 onDismiss는 아무것도 다시 그리지 않는 목이다.
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore, setNavigate } from "../../../../api";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { INPUT_PATH } from "../../../../app/routes";
import { UndoToast } from "../../../result/UndoToast";
import { flush, tick } from "../../__tests__/helpers";
import { deferred, planView } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
  appPlanStore.clear();
});

afterEach(() => {
  cleanup();
  setNavigate(null);
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  appPlanStore.clear();
});

describe("AC-37 폐기 직후 React 정리 전에 도착한 늦은 NO_PLAN은 새 계획을 비우지 않는다 (P-75)", () => {
  async function reachPendingUndo(onDismiss: () => void) {
    const oldUndo = deferred<Response>();
    const navigations: string[] = [];
    setNavigate((to) => navigations.push(to));
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        if (method === "POST" && url === "/api/plan/undo") return oldUndo.promise;
        if (method === "GET" && url === "/api/plan") return jsonResponse(200, planView(2));
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(2));
    render(<UndoToast appliedRevision={2} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole("button", { name: "되돌리기" }));
    await flush();
    return { oldUndo, navigations };
  }

  it("계획이 비워져 토스트가 폐기된 뒤 새 계획이 들어와도, 이전 P-5의 NO_PLAN 완료는 새 계획을 비우지 않는다", async () => {
    const onDismiss = vi.fn();
    const { oldUndo, navigations } = await reachPendingUndo(onDismiss);

    appPlanStore.acceptNoPlan(2); // 계획이 비워진다 → 토스트 폐기
    appPlanStore.acceptPlanView(planView(1)); // 새 계획이 revision을 다시 1부터 쓴다
    expect(onDismiss).toHaveBeenCalled();
    navigations.length = 0;

    oldUndo.resolve(problemResponse(409, "NO_PLAN"));
    await tick(0);
    await flush();

    expect(appPlanStore.getState().revision, "늦은 NO_PLAN이 새 계획을 비웠다").toBe(1);
    expect(appPlanStore.getState().view).not.toBeNull();
    expect(navigations).not.toContain(INPUT_PATH);
  });

  it("10초가 지나 토스트가 닫힌 뒤 늦게 온 NO_PLAN도 같은 계정의 새 계획을 건드리지 않는다", async () => {
    const onDismiss = vi.fn();
    const { oldUndo, navigations } = await reachPendingUndo(onDismiss);

    await tick(10_000);
    expect(onDismiss).toHaveBeenCalled();
    appPlanStore.acceptPlanView(planView(5)); // 같은 계정의 새 계획
    navigations.length = 0;

    oldUndo.resolve(problemResponse(409, "NO_PLAN"));
    await tick(0);
    await flush();

    expect(appPlanStore.getState().revision).toBe(5);
    expect(navigations).not.toContain(INPUT_PATH);
  });
});
