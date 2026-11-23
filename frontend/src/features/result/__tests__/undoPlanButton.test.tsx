// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import NavigateBridge from "../../../app/NavigateBridge";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { ResultPage } from "../ResultPage";

// 결과 화면 '직전 계획으로 되돌리기' 상시 버튼(#263, D-095, 계약 C12a-8). 실서버 모드로 fetch만 바꾼다.

const BUTTON = "직전 계획으로 되돌리기";

function planBody(revision: number, undoAvailable: boolean) {
  return { ...(planMock.responses[0].body as Record<string, unknown>), revision, undoAvailable };
}

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

/** GET /api/plan은 plans를 차례로(마지막은 반복) 돌려주고, 되돌리기는 undoResponse를 돌려준다. */
function stubServer(plans: unknown[], undoResponse: () => Response) {
  const calls: Call[] = [];
  let planReads = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://localhost");
      const method = init?.method ?? "GET";
      calls.push({ method, path: url.pathname, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
      if (method === "GET" && url.pathname === "/api/plan") {
        const plan = plans[Math.min(planReads, plans.length - 1)];
        planReads += 1;
        return jsonResponse(200, plan);
      }
      if (method === "POST" && url.pathname === "/api/plan/undo") return undoResponse();
      return jsonResponse(200, { messages: [], hasPending: false });
    }),
  );
  return { calls, planReads: () => planReads };
}

function renderResult() {
  render(
    <MemoryRouter initialEntries={[RESULT_PATH]}>
      <NavigateBridge />
      <Routes>
        <Route path={RESULT_PATH} element={<ResultPage />} />
        <Route path={INPUT_PATH} element={<div>입력화면</div>} />
        <Route path={LOGIN_PATH} element={<div>로그인화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function conditionBar() {
  return screen.findByRole("region", { name: "내 계획 조건" });
}

describe("직전 계획으로 되돌리기 상시 버튼 (#263, D-095)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("undoAvailable이 false면 버튼이 없다", async () => {
    stubServer([planBody(12, false)], () => jsonResponse(200, { revision: 13 }));
    renderResult();

    const bar = await conditionBar();
    expect(within(bar).queryByRole("button", { name: BUTTON })).toBeNull();
  });

  it("undoAvailable이 true면 조건 바에 버튼이 보이고, 누르면 현재 revision으로 P-5를 부른 뒤 다시 불러와 버튼이 사라진다", async () => {
    const server = stubServer([planBody(13, true), planBody(14, false)], () => jsonResponse(200, { revision: 14 }));
    renderResult();

    const bar = await conditionBar();
    fireEvent.click(within(bar).getByRole("button", { name: BUTTON }));

    await waitFor(() => expect(within(bar).queryByRole("button", { name: BUTTON })).toBeNull());
    expect(server.calls).toContainEqual({ method: "POST", path: "/api/plan/undo", body: { expectedRevision: 13 } });
    expect(server.planReads()).toBe(2);
    expect(planStore.getState().revision).toBe(14);
  });

  it("NOTHING_TO_UNDO면 이미 되돌렸다고 안내하고 다시 불러온다", async () => {
    const server = stubServer([planBody(13, true), planBody(13, false)], () => problem(409, "NOTHING_TO_UNDO"));
    renderResult();

    fireEvent.click(within(await conditionBar()).getByRole("button", { name: BUTTON }));

    expect(await screen.findByText("이미 되돌렸어요")).toBeTruthy();
    await waitFor(() => expect(server.planReads()).toBe(2));
  });

  it("PLAN_CHANGED면 다른 곳에서 바뀌었다고 안내하고 다시 불러온다", async () => {
    const server = stubServer([planBody(13, true), planBody(15, true)], () => problem(409, "PLAN_CHANGED"));
    renderResult();

    fireEvent.click(within(await conditionBar()).getByRole("button", { name: BUTTON }));

    expect(await screen.findByText(/계획이 다른 곳에서 바뀌었어요/)).toBeTruthy();
    await waitFor(() => expect(server.planReads()).toBe(2));
  });

  it("NO_PLAN이면 입력 화면으로 간다", async () => {
    stubServer([planBody(13, true)], () => problem(409, "NO_PLAN"));
    renderResult();

    fireEvent.click(within(await conditionBar()).getByRole("button", { name: BUTTON }));

    expect(await screen.findByText("입력화면")).toBeTruthy();
  });

  it("그 밖의 실패면 되돌리지 못했다고 안내하고 버튼은 남아 다시 누를 수 있다", async () => {
    stubServer([planBody(13, true)], () => problem(500, "INTERNAL_ERROR"));
    renderResult();

    const bar = await conditionBar();
    fireEvent.click(within(bar).getByRole("button", { name: BUTTON }));

    expect(await screen.findByText("되돌리지 못했어요. 다시 시도해 주세요")).toBeTruthy();
    expect((within(bar).getByRole("button", { name: BUTTON }) as HTMLButtonElement).disabled).toBe(false);
  });
});
