// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import comparisonMock from "../../../mocks/chat/get-comparison.200.json";
import afterApplyMock from "../../../mocks/plan/get-plan.200-after-apply.json";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { ResultPage } from "../ResultPage";

// 결과 화면 비교 영역에서 비교 → 확인창 → 적용(P-6)까지(#68, C12a-9-2). 실서버 모드로 fetch만 바꾼다.

const comparisonBody = (() => {
  const { messageId: _messageId, ...rest } = comparisonMock.responses[0].body as Record<string, unknown>;
  return rest;
})();

function jsonResponse(status: number, body: unknown): Response {
  const contentType = status >= 400 ? "application/problem+json" : "application/json";
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

function problem(status: number, code: string): Response {
  return jsonResponse(status, { type: "about:blank", status, code, errors: [] });
}

interface Call {
  method: string;
  path: string;
  body: unknown;
}

/** 경로별 응답. GET /api/plan은 적용 전·후 두 번을 차례로 돌려준다. */
function stubServer(applyResponse: () => Response) {
  const calls: Call[] = [];
  let planReads = 0;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    calls.push({ method, path: url.pathname, body });
    if (method === "GET" && url.pathname === "/api/plan") {
      planReads += 1;
      return jsonResponse(200, (planReads === 1 ? planMock : afterApplyMock).responses[0].body);
    }
    if (method === "POST" && url.pathname === "/api/compare") return jsonResponse(200, comparisonBody);
    if (method === "POST" && url.pathname === "/api/plan/apply") return applyResponse();
    return jsonResponse(200, { messages: [], hasPending: false });
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls, planReads: () => planReads };
}

function renderResult() {
  render(
    <MemoryRouter initialEntries={[RESULT_PATH]}>
      <Routes>
        <Route path={RESULT_PATH} element={<ResultPage />} />
        <Route path={INPUT_PATH} element={<div>입력화면</div>} />
        <Route path={LOGIN_PATH} element={<div>로그인화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function applyButton() {
  return screen.getByRole("button", { name: "바꾼 조건을 내 계획으로 적용" }) as HTMLButtonElement;
}

/** 월 저축액을 50만원으로 바꿔 비교하고 표가 뜰 때까지 기다린다. */
async function compareMonthly50() {
  const section = await screen.findByRole("region", { name: "조건 변경 비교" });
  fireEvent.click(within(section).getByRole("checkbox", { name: "월 저축액" }));
  fireEvent.change(within(section).getByRole("textbox", { name: "월 저축액" }), { target: { value: "50" } });
  fireEvent.click(within(section).getByRole("button", { name: "비교하기" }));
  await waitFor(() => expect(applyButton().disabled).toBe(false));
}

async function openAndConfirm() {
  fireEvent.click(applyButton());
  const dialog = screen.getByRole("dialog", { name: "바꾼 조건을 내 계획에 적용할까요?" });
  fireEvent.click(within(dialog).getByRole("button", { name: "적용하기" }));
}

describe("결과 화면 비교 → 적용 (#68, C12a-9-2)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("비교하기는 실서버 POST /api/compare를 부르고 결과 표를 보여 준다", async () => {
    const server = stubServer(() => jsonResponse(200, { revision: 13 }));
    renderResult();

    await compareMonthly50();

    expect(server.calls).toContainEqual({
      method: "POST",
      path: "/api/compare",
      body: { conditionChanges: { monthlySavingAmount: 500000 } },
    });
  });

  it("확인창에서 취소하면 아무것도 적용하지 않는다", async () => {
    const server = stubServer(() => jsonResponse(200, { revision: 13 }));
    renderResult();
    await compareMonthly50();

    fireEvent.click(applyButton());
    fireEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(server.calls.some((call) => call.path === "/api/plan/apply")).toBe(false);
    expect(planStore.getState().revision).toBe(12);
  });

  it("적용하기를 누르면 P-6을 부르고 새 revision·되돌리기 토스트·결과 재조회로 이어진다", async () => {
    const server = stubServer(() => jsonResponse(200, { revision: 13 }));
    renderResult();
    await compareMonthly50();

    await openAndConfirm();

    await waitFor(() => expect(screen.getByText("조건을 적용했어요.")).toBeTruthy());
    expect(server.calls).toContainEqual({
      method: "POST",
      path: "/api/plan/apply",
      body: { path: "COMPARE", expectedRevision: 12, conditionChanges: { monthlySavingAmount: 500000 } },
    });
    expect(screen.queryByRole("dialog")).toBeNull();
    await waitFor(() => expect(server.planReads()).toBe(2));
    expect(planStore.getState().revision).toBe(13);
    // 적용한 비교는 끝났다. 비교 영역을 비우고 버튼은 다시 비교할 때까지 꺼진다
    expect(applyButton().disabled).toBe(true);
  });

  it("PLAN_CHANGED면 창을 닫고 안내한 뒤 계획을 다시 불러온다", async () => {
    const server = stubServer(() => problem(409, "PLAN_CHANGED"));
    renderResult();
    await compareMonthly50();

    await openAndConfirm();

    await waitFor(() => expect(screen.getByText("계획이 다른 곳에서 바뀌었어요")).toBeTruthy());
    expect(screen.queryByRole("dialog")).toBeNull();
    await waitFor(() => expect(server.planReads()).toBe(2));
  });

  it("422면 창을 닫고 적용할 수 없는 값이라고 안내하며 다시 비교할 때까지 버튼을 끈다", async () => {
    stubServer(() => problem(422, "VALIDATION_FAILED"));
    renderResult();
    await compareMonthly50();

    await openAndConfirm();

    await waitFor(() => expect(screen.getByText("지금 기준으로는 적용할 수 없는 값이에요")).toBeTruthy());
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(applyButton().disabled).toBe(true);
  });

  it("INTERNAL_ERROR면 창을 연 채로 실패를 알리고 다시 누를 수 있다", async () => {
    stubServer(() => problem(500, "INTERNAL_ERROR"));
    renderResult();
    await compareMonthly50();

    await openAndConfirm();

    const dialog = await screen.findByRole("dialog");
    await waitFor(() => expect(within(dialog).getByRole("alert").textContent).toBe("적용하지 못했어요"));
    expect((within(dialog).getByRole("button", { name: "적용하기" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("NO_PLAN이면 입력 화면으로 간다", async () => {
    stubServer(() => problem(409, "NO_PLAN"));
    renderResult();
    await compareMonthly50();

    await openAndConfirm();

    expect(await screen.findByText("입력화면")).toBeTruthy();
  });

  it("비교한 뒤 내 계획이 바뀌면(revision 변경) 버튼을 끈다", async () => {
    stubServer(() => jsonResponse(200, { revision: 13 }));
    renderResult();
    await compareMonthly50();

    planStore.acceptRevision(13);

    await waitFor(() => expect(applyButton().disabled).toBe(true));
  });
});
