// @vitest-environment jsdom
// Issue #53 AC-20: 목 모드(래퍼 #13 + src/mocks/*.json)에서 복원 → 카드 → 적용 → 결과 갱신 흐름을 확인한다.
// 진짜 래퍼·진짜 store·진짜 폴링 레지스트리를 쓰고, 네트워크 대신 목 JSON을 쓴다.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { api as appApi, planStore as appPlanStore, polling as appPolling, setNavigate } from "../../../../api";
import { jsonResponse, readMock } from "../../../../api/__tests__/helpers";
import { mockFetch, resetMocks, useMock } from "../../../../api/mock";
import { INPUT_PATH } from "../../../../app/routes";
import { ChatWindow } from "../../index";
import { copy, makeMockEnv } from "../../__tests__/helpers";
import { applyButton, applyButtons, buttonOf, cardOf, comparisonToast, planView } from "./helpers";

beforeAll(async () => {
  await import("../../../../api/mock");
});

beforeEach(() => {
  resetMocks();
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  resetMocks();
});

const settle = async () => {
  await act(async () => {
    for (let i = 0; i < 30; i += 1) await new Promise((resolve) => setImmediate(resolve));
  });
};

const advance = async (ms: number) => {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
  await settle();
};

async function mountMock() {
  const env = makeMockEnv("/result");
  act(() => env.store.acceptPlanView(planView(12)));
  render(<ChatWindow deps={env.deps} />);
  await advance(0);
  await settle();
  return env;
}

const comparisonGets = (env: ReturnType<typeof makeMockEnv>) =>
  env.requests.filter((r) => r.method === "GET" && r.path.endsWith("/comparison")).map((r) => r.path);

describe("AC-20 목 모드 시나리오", () => {
  it("restore-history: F-4는 1031에만, 1027·1029는 접힌 비활성 카드, 적용하면 revision 13·결과 재조회·되돌리기 토스트·모든 버튼 비활성", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-plan.200-after-apply.json");
    const env = await mountMock();

    expect(comparisonGets(env)).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(cardOf(1027).getAttribute("data-collapsed")).toBe("true");
    expect(cardOf(1029).getAttribute("data-collapsed")).toBe("true");
    expect(buttonOf(1027).disabled).toBe(true);
    expect(buttonOf(1029).disabled).toBe(true);
    expect(buttonOf(1031).disabled).toBe(false);

    fireEvent.click(buttonOf(1031));
    await settle();
    await settle();

    expect(env.requests.filter((r) => r.method === "POST")).toEqual([
      { method: "POST", path: "/api/chat/messages/1031/apply", body: { expectedRevision: 12 } },
    ]);
    expect(env.store.getState().revision).toBe(13);
    expect(env.store.getState().view?.revision).toBe(13);
    expect(screen.queryByRole("button", { name: "되돌리기" })).not.toBeNull();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(comparisonGets(env)).toEqual(["/api/chat/messages/1031/comparison"]);
  });

  it("post-apply.409-stale: 안내 토스트와 버튼 비활성, 계획 재조회 없음", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("post-apply.409-stale.json");
    const env = await mountMock();
    fireEvent.click(buttonOf(1031));
    await settle();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(env.requests.some((r) => r.path === "/api/plan")).toBe(false);
  });

  it("get-comparison.500: 카드 자리에 실패 안내, 버튼 없음", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-comparison.500.json");
    await mountMock();
    expect(
      document.querySelector('[data-testid="chat-comparison-failed"][data-message-id="1031"]')?.textContent,
    ).toContain(copy["comparison.loadFailed"]);
    expect(applyButton(1031)).toBeNull();
  });
});

// ---------------------------------------------------------------- 앱 인스턴스 + 목 JSON
// 앱 기본 인스턴스(api 래퍼·공유 store·폴링, src/api/index.ts)를 그대로 쓰고, 그 래퍼의 전송(fetch)만 목 JSON(mockFetch)으로 돌린다.
// VITE_API_MODE=mock으로 띄운 앱과 같은 흐름이다(그 모드에서도 래퍼는 mockFetch를 부른다). 되돌리기 토스트(#51)도 같은 인스턴스를 쓴다.
type AppRequest = { method: string; path: string; body?: unknown };

/** 목 JSON 대신 응답할 요청이 있으면 Response를, 아니면 undefined(목 JSON으로)를 돌려준다. */
type Override = (method: string, url: string) => Response | undefined;

function mountApp(override?: Override) {
  const requests: AppRequest[] = [];
  const navigations: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.pathname + input.search : input.url;
      const method = (init?.method ?? "GET").toUpperCase();
      const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
      requests.push({ method, path: url, body });
      return override?.(method, url) ?? mockFetch(url, { method });
    }),
  );
  setNavigate((to) => navigations.push(to));
  act(() => appPlanStore.acceptPlanView(planView(12)));
  render(<ChatWindow deps={{ api: appApi, polling: appPolling, planStore: appPlanStore }} />);
  return { requests, navigations };
}

async function mountAppSettled(override?: Override) {
  const env = mountApp(override);
  await advance(0);
  await settle();
  return env;
}

const appComparisonGets = (requests: AppRequest[]) =>
  requests.filter((r) => r.method === "GET" && r.path.endsWith("/comparison")).map((r) => r.path);
const planGetCount = (requests: AppRequest[]) => requests.filter((r) => r.method === "GET" && r.path === "/api/plan").length;
const undoToastButton = () => screen.queryByRole("button", { name: "되돌리기" });

async function clickAndSettle(button: HTMLElement) {
  fireEvent.click(button);
  await settle();
  await settle();
}

describe("AC-20 목 모드 시나리오 (앱 인스턴스)", () => {
  beforeEach(() => {
    appPlanStore.clear();
  });

  afterEach(() => {
    cleanup();
    appPolling.stopAll();
    appPlanStore.clear();
    setNavigate(null);
    vi.unstubAllGlobals();
  });

  it("되돌리기: 적용 → 토스트의 '되돌리기'가 expectedRevision 13으로 나가고, revision 14가 되면 토스트가 닫히고 모든 버튼이 비활성이다", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-plan.200-after-apply.json");
    useMock("post-undo.200.json");
    const { requests } = await mountAppSettled();
    expect(appComparisonGets(requests)).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(buttonOf(1031).disabled).toBe(false);

    await clickAndSettle(buttonOf(1031));
    expect(appPlanStore.getState().revision).toBe(13);
    const undo = undoToastButton();
    expect(undo, "되돌리기 토스트가 없다").not.toBeNull();

    await clickAndSettle(undo as HTMLElement);
    expect(requests.filter((r) => r.method === "POST" && r.path === "/api/plan/undo").map((r) => r.body)).toEqual([
      { expectedRevision: 13 },
    ]);
    expect(appPlanStore.getState().revision).toBe(14);
    expect(undoToastButton()).toBeNull();
    expect(applyButtons().length).toBeGreaterThanOrEqual(3);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(appComparisonGets(requests)).toEqual(["/api/chat/messages/1031/comparison"]);
  });

  it("되돌리기 뒤 결과 화면 데이터: 계획을 다시 불러와(get-plan.200-after-undo) view가 revision 14·직전 조건(월 저축액 300000)이고 시작월은 그대로다", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-plan.200-after-apply.json");
    useMock("post-undo.200.json");
    const { requests } = await mountAppSettled();

    await clickAndSettle(buttonOf(1031));
    const applied = appPlanStore.getState().view;
    expect(applied?.revision).toBe(13);
    const appliedBody = applied?.body as { plan: { monthlySavingAmount: number }; result: { calcStartMonth: string } };
    expect(appliedBody.plan.monthlySavingAmount).toBe(500000);
    const startMonth = appliedBody.result.calcStartMonth;
    expect(startMonth).toBe("2026-11");

    useMock("get-plan.200-after-undo.json");
    const undo = undoToastButton();
    expect(undo, "되돌리기 토스트가 없다").not.toBeNull();
    await clickAndSettle(undo as HTMLElement);
    expect(planGetCount(requests)).toBe(2);
    const undone = appPlanStore.getState().view;
    expect(appPlanStore.getState().revision).toBe(14);
    expect(undone?.revision).toBe(14);
    const undoneBody = undone?.body as { plan: { monthlySavingAmount: number }; result: { calcStartMonth: string } };
    expect(undoneBody.plan.monthlySavingAmount).toBe(300000);
    expect(undoneBody.result.calcStartMonth).toBe(startMonth);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("대화 적용 뒤 결과 화면 데이터: 다시 불러온 view(get-plan.200-after-apply)는 바꾼 조건(월 저축액 500000)이고 시작월은 적용 전에 저장된 값과 같다", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-plan.200-after-apply.json");
    const { requests } = await mountAppSettled();
    const before = appPlanStore.getState().view?.body as { plan: { monthlySavingAmount: number }; result: { calcStartMonth: string } };
    const savedStartMonth = before.result.calcStartMonth;
    expect(before.plan.monthlySavingAmount).toBe(300000);

    await clickAndSettle(buttonOf(1031));
    expect(planGetCount(requests)).toBe(1);
    const applied = appPlanStore.getState().view;
    expect(applied?.revision).toBe(13);
    const appliedBody = applied?.body as { plan: { monthlySavingAmount: number }; result: { calcStartMonth: string } };
    expect(appliedBody.plan.monthlySavingAmount).toBe(500000);
    expect(appliedBody.result.calcStartMonth).toBe(savedStartMonth);
  });

  it("post-apply.500 → 재시도: '적용하지 못했어요' 뒤 버튼이 활성 그대로이고, 다시 누르면(post-apply.200) 적용된다", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-plan.200-after-apply.json");
    useMock("post-apply.500.json");
    const { requests } = await mountAppSettled();

    await clickAndSettle(buttonOf(1031));
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    expect(buttonOf(1031).disabled).toBe(false);
    expect(appPlanStore.getState().revision).toBe(12);
    expect(planGetCount(requests)).toBe(0);

    useMock("post-apply.200.json");
    await clickAndSettle(buttonOf(1031));
    const applies = requests.filter((r) => r.method === "POST" && r.path === "/api/chat/messages/1031/apply");
    expect(applies.map((r) => r.body)).toEqual([{ expectedRevision: 12 }, { expectedRevision: 12 }]);
    expect(appPlanStore.getState().revision).toBe(13);
    expect(comparisonToast()).toBeNull();
    expect(undoToastButton()).not.toBeNull();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("post-apply.409-plan-changed: 안내 뒤 계획을 다시 불러와(get-plan.200-after-apply) revision 13, 모든 버튼 비활성, 되돌리기 토스트 없음", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-plan.200-after-apply.json");
    useMock("post-apply.409-plan-changed.json");
    const { requests } = await mountAppSettled();
    await clickAndSettle(buttonOf(1031));
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(planGetCount(requests)).toBe(1);
    expect(appPlanStore.getState().revision).toBe(13);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(undoToastButton()).toBeNull();
  });

  it("post-apply.409-no-plan: store를 비우고 입력 화면으로, 대화창 메시지는 그대로", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("post-apply.409-no-plan.json");
    const { navigations } = await mountAppSettled();
    await clickAndSettle(buttonOf(1031));
    expect(appPlanStore.getState().revision).toBeNull();
    expect(navigations).toContain(INPUT_PATH);
    for (const id of [1027, 1029, 1031]) {
      expect(document.querySelector(`[data-testid="chat-message"][data-message-id="${id}"]`)).not.toBeNull();
    }
  });

  it("post-apply.422-validation: '지금 기준으로는 적용할 수 없는 값이에요' + 버튼 비활성, 계획 재조회 없음", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("post-apply.422-validation.json");
    const { requests } = await mountAppSettled();
    await clickAndSettle(buttonOf(1031));
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyNotApplicable"]);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(planGetCount(requests)).toBe(0);
    expect(appPlanStore.getState().revision).toBe(12);
  });

  it("post-apply.404: 버튼 비활성, 계획 재조회 없음", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("post-apply.404.json");
    const { requests } = await mountAppSettled();
    await clickAndSettle(buttonOf(1031));
    expect(buttonOf(1031).disabled).toBe(true);
    expect(planGetCount(requests)).toBe(0);
    expect(appPlanStore.getState().revision).toBe(12);
  });

  it("get-comparison.404: 카드 자리가 비고 버튼이 없다", async () => {
    useMock("get-messages.200-restore-history.json");
    useMock("get-comparison.404.json");
    const { requests } = await mountAppSettled();
    expect(appComparisonGets(requests)).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(document.querySelector('[data-testid="chat-comparison"][data-message-id="1031"]')).toBeNull();
    expect(applyButton(1031)).toBeNull();
  });

  it("get-comparison.200-not-applicable: 복원 목록의 최신 RECALC_RESULT가 1027이면 목 기본 경로로 1027의 F-4를 1회 부르고, 최신 카드라도 applicable=false라 펼쳐지되 버튼이 비활성이다", async () => {
    // 기존 복원 데이터(restore-history)에서 1027까지만 남긴 목록. 1027 F-4는 목 래퍼의 기본 파일(get-comparison.200-not-applicable, messageId 1027)이다.
    const restore = readMock("get-messages.200-restore-history.json").responses[0].body as { messages: Array<{ id: number }> };
    const upTo1027 = { ...restore, messages: restore.messages.filter((m) => m.id <= 1027), hasPending: false, answerPending: false };
    const notApplicable = readMock("get-comparison.200-not-applicable.json").responses[0].body as { messageId: number; applicable: boolean };
    expect(notApplicable).toMatchObject({ messageId: 1027, applicable: false });

    const { requests } = await mountAppSettled((method, url) =>
      method === "GET" && url.split("?")[0] === "/api/chat/messages" ? jsonResponse(200, upTo1027) : undefined,
    );
    expect(appComparisonGets(requests)).toEqual(["/api/chat/messages/1027/comparison"]);
    expect(cardOf(1027).getAttribute("data-collapsed")).toBe("false");
    expect(buttonOf(1027).disabled).toBe(true);
    expect(applyButtons().filter((button) => !button.disabled)).toHaveLength(0);
  });
});
