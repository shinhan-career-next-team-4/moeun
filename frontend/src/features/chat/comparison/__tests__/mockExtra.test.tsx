// @vitest-environment jsdom
// spec.md "제약"의 구현 단계 TODO: AC-20 목 흐름 3개(etf0·F-4 400·post-apply.422-field-not-changeable)와 AC-15 지연 응답.
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { api as appApi, planStore as appPlanStore, polling as appPolling, setNavigate } from "../../../../api";
import { jsonResponse, readMock } from "../../../../api/__tests__/helpers";
import { mockFetch, resetMocks, useMock } from "../../../../api/mock";
import { ChatWindow } from "../../index";
import { copy, doneMsg } from "../../__tests__/helpers";
import { applyButton, applyButtons, buttonOf, card, comparisonToast, planView, scenarioRows } from "./helpers";

beforeAll(async () => {
  await import("../../../../api/mock");
});

beforeEach(() => {
  resetMocks();
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
  appPlanStore.clear();
});

afterEach(() => {
  cleanup();
  appPolling.stopAll();
  appPlanStore.clear();
  setNavigate(null);
  vi.unstubAllGlobals();
  vi.useRealTimers();
  resetMocks();
});

const settle = async () => {
  await act(async () => {
    for (let i = 0; i < 30; i += 1) await new Promise((resolve) => setImmediate(resolve));
  });
};

type Override = (method: string, url: string) => Response | undefined;

async function mountApp(override?: Override) {
  const requests: Array<{ method: string; path: string }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.pathname + input.search : input.url;
      const method = (init?.method ?? "GET").toUpperCase();
      requests.push({ method, path: url });
      return override?.(method, url) ?? mockFetch(url, { method });
    }),
  );
  setNavigate(() => {});
  act(() => appPlanStore.acceptPlanView(planView(12)));
  render(<ChatWindow deps={{ api: appApi, polling: appPolling, planStore: appPlanStore }} />);
  await act(async () => {
    vi.advanceTimersByTime(0);
  });
  await settle();
  return requests;
}

const planGets = (requests: Array<{ method: string; path: string }>) =>
  requests.filter((r) => r.method === "GET" && r.path === "/api/plan").length;

describe("AC-20 목 모드 추가 흐름", () => {
  it("get-comparison.200-etf0: 1033 카드는 AVG 한 행이고 버튼이 활성이다", async () => {
    const restore = structuredClone(readMock("get-messages.200-restore-history.json").responses[0].body);
    restore.messages.push(doneMsg(1033, "RECALC_RESULT", "ETF 비중을 0%로 바꾸면 평균일 때 달성해요"));
    await mountApp((method, url) => (method === "GET" && url === "/api/chat/messages" ? jsonResponse(200, restore) : undefined));
    expect(scenarioRows(1033).map((row) => row.getAttribute("data-scenario"))).toEqual(["AVG"]);
    expect(buttonOf(1033).disabled).toBe(false);
    expect(applyButton(1031)?.disabled ?? true).toBe(true);
  });

  it("get-comparison.400: 카드 자리가 비고 버튼이 없다", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-comparison.400.json");
    await mountApp();
    expect(card(1031)).toBeNull();
    expect(applyButton(1031)).toBeNull();
    expect(card(1029)?.getAttribute("data-collapsed")).toBe("true");
  });

  it("post-apply.422-field-not-changeable: toast.applyNotApplicable, 버튼 비활성, /api/plan 호출 없음, revision 12", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("post-apply.422-field-not-changeable.json");
    const requests = await mountApp();
    fireEvent.click(buttonOf(1031));
    await settle();
    await settle();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyNotApplicable"]);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(planGets(requests)).toBe(0);
    expect(appPlanStore.getState().revision).toBe(12);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });
});
