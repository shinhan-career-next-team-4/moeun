// @vitest-environment jsdom
// Issue #54: 앱 기본 인스턴스(api·planStore·polling 싱글턴, 실서버 모드 + fetch 대역)로 묶어 본다.
// - AC-1: 결과 화면(#49)은 설명(F-3)을 기다리지 않고 그려진다.
// - AC-3: 설명이 FAILED여도 결과 화면은 그대로다.
// - AC-7(결정 3 T-4): 기존 되돌리기 진입점(#51 UndoToast)을 실제로 실행해, store 갱신 전에는 F-3이 나가지 않고
//   갱신 뒤 새 calcRunId로 한 번만 나가는지 본다. 적용(#53) 진입점은 아직 develop에 없어 정적 검사(callSites.test.ts)가 맡는다.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore } from "../../../../api";
import { deferred } from "../../../../api/__tests__/helpers";
import { ResultPage, UndoToast } from "../../../result";
import { ChatWindow } from "../../index";
import { copy } from "../../__tests__/helpers";
import { chatPage, explanationFailed, explanationPending, explanationServer, F3_PATH, freshId, view } from "./helpers";

type Route = (method: string, path: string, body: unknown) => Response | Promise<Response>;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** 요청을 기록하고 라우터 함수로 응답하는 fetch 대역. */
function stubFetch(route: Route) {
  const calls: Array<{ method: string; path: string; body: unknown }> = [];
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    const path = String(input);
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    calls.push({ method, path, body });
    return route(method, path, body);
  });
  vi.stubGlobal("fetch", fetchSpy);
  const f3Bodies = () => calls.filter((c) => c.method === "POST" && c.path === F3_PATH).map((c) => c.body);
  const planGets = () => calls.filter((c) => c.method === "GET" && c.path === "/api/plan");
  return { calls, f3Bodies, planGets };
}

beforeEach(() => {
  planStore.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  planStore.clear();
});

function renderResultWithChat() {
  return render(
    <MemoryRouter>
      <ResultPage />
      <ChatWindow />
    </MemoryRouter>,
  );
}

describe("AC-1 결과는 설명을 기다리지 않고 보인다 (FR-AI-01, TBD-26)", () => {
  it("결과 화면이 계획을 불러와 그리면 F-3이 자동으로 나가고, F-3 응답이 오지 않아도 결과 요약이 보인다", async () => {
    const x = freshId();
    const never = deferred<Response>();
    const net = stubFetch((method, path) => {
      if (method === "GET" && path === "/api/plan") return json(200, view(12, "CALCULATED", x));
      if (method === "POST" && path === F3_PATH) return never.promise; // 설명 요청은 끝나지 않는다
      if (method === "GET" && path.startsWith("/api/chat/messages")) return json(200, chatPage([], false));
      throw new Error(`예상하지 못한 요청: ${method} ${path}`);
    });
    renderResultWithChat();

    expect(await screen.findByLabelText("결과 요약")).toBeTruthy();
    await waitFor(() => expect(net.f3Bodies()).toEqual([{ calcRunId: x }]));
    expect(screen.getByLabelText("결과 요약").textContent).toContain("약 1,712만원");
    expect(net.planGets()).toHaveLength(1); // 결과 화면의 loadPlan() 한 번. 대화창은 GET /api/plan을 직접 부르지 않는다
  });
});

describe("AC-3 설명이 FAILED여도 결과 화면은 그대로다 (FR-AI-04)", () => {
  it("F-3 202 뒤 폴링으로 FAILED 설명이 오면 대화창에 실패 안내가 보이고 결과 요약·store는 바뀌지 않는다", async () => {
    const x = freshId();
    // 서버 상태 흉내: F-3이 202를 받기 전 F-2는 빈 목록, 받은 뒤에는 대기 → FAILED.
    const server = explanationServer([
      chatPage([explanationPending(4301)], true),
      chatPage([explanationFailed(4301, copy["chat.failed"])], false),
    ]);
    const net = stubFetch((method, path) => {
      if (method === "GET" && path === "/api/plan") return json(200, view(12, "CALCULATED", x));
      if (method === "POST" && path === F3_PATH) return json(202, server.f3Reply());
      if (method === "GET" && path.startsWith("/api/chat/messages")) return json(200, server.getReply());
      throw new Error(`예상하지 못한 요청: ${method} ${path}`);
    });
    renderResultWithChat();
    expect(await screen.findByLabelText("결과 요약")).toBeTruthy();
    await waitFor(() => expect(net.f3Bodies()).toEqual([{ calcRunId: x }]));
    const settled = planStore.getState();

    await waitFor(
      () => {
        const failed = screen
          .queryAllByTestId("chat-message")
          .find((el) => el.getAttribute("data-message-id") === "4301");
        expect(failed?.getAttribute("data-status")).toBe("FAILED");
        expect(failed?.textContent).toContain(copy["chat.failed"]);
      },
      { timeout: 4000 },
    );
    expect(screen.getByLabelText("결과 요약").textContent).toContain("약 1,712만원");
    expect(planStore.getState()).toBe(settled);
    expect(net.planGets()).toHaveLength(1);
  });
});

describe("AC-7 되돌리기(#51) 진입점은 F-3을 부르지 않고, 재조회로 바뀐 calcRunId를 대화창 effect가 한 번 보낸다 (H-11c, 결정 3 T-4)", () => {
  it("되돌리기 성공 → revision 갱신 → 재조회 응답 전에는 F-3이 없고, 재조회로 view가 바뀐 뒤 새 calcRunId로 한 번만 나간다", async () => {
    const x = freshId();
    const y = freshId();
    const reload = deferred<Response>();
    const net = stubFetch((method, path) => {
      if (method === "POST" && path === "/api/plan/undo") return json(200, { revision: 14 });
      if (method === "GET" && path === "/api/plan") return reload.promise;
      if (method === "POST" && path === F3_PATH) return json(202, {});
      if (method === "GET" && path.startsWith("/api/chat/messages")) return json(200, chatPage([], false));
      throw new Error(`예상하지 못한 요청: ${method} ${path}`);
    });
    planStore.acceptPlanView(view(13, "CALCULATED", x));
    const dismiss = vi.fn();
    render(
      <>
        <ChatWindow />
        <UndoToast appliedRevision={13} onDismiss={dismiss} />
      </>,
    );
    await waitFor(() => expect(net.f3Bodies()).toEqual([{ calcRunId: x }]));

    fireEvent.click(screen.getByRole("button", { name: "되돌리기" }));
    await waitFor(() => expect(net.planGets()).toHaveLength(1));
    expect(planStore.getState().revision).toBe(14);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(net.f3Bodies()).toEqual([{ calcRunId: x }]); // store의 view가 바뀌기 전: 되돌리기 코드도, effect도 보내지 않는다

    reload.resolve(json(200, view(14, "CALCULATED", y)));
    await waitFor(() => expect(net.f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(net.f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);
  });
});
