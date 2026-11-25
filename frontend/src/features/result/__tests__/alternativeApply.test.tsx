// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import NavigateBridge from "../../../app/NavigateBridge";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import noneMock from "../../../mocks/alternative/post-alternatives.200-none.json";
import periodMock from "../../../mocks/alternative/post-alternatives.200-period.json";
import firstMock from "../../../mocks/alternative/post-alternatives.200.json";
import afterApplyMock from "../../../mocks/plan/get-plan.200-after-apply.json";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { ResultPage } from "../ResultPage";

// 결과 화면 대안 카드를 실서버 대안 API(A-1)에 잇고 P-6 ALTERNATIVE로 적용한다(#258, D-096). 실서버 모드로 fetch만 바꾼다.

const first = firstMock.responses[0].body as { alternatives: { display: Record<string, string>; changes: Record<string, number> }[] };
const monthly = first.alternatives[0];

function jsonResponse(status: number, body: unknown): Response {
  const contentType = status >= 400 ? "application/problem+json" : "application/json";
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

interface Call {
  method: string;
  path: string;
  body: unknown;
}

function problem(status: number, code: string): Response {
  return jsonResponse(status, { type: "about:blank", status, code, errors: [] });
}

function stubServer(
  options: { alternatives?: (body: { targetPeriodMonths?: number }) => unknown; apply?: () => Response } = {},
) {
  const calls: Call[] = [];
  let planReads = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://localhost");
      const method = init?.method ?? "GET";
      const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
      calls.push({ method, path: url.pathname, body });
      if (method === "GET" && url.pathname === "/api/plan") {
        planReads += 1;
        return jsonResponse(200, (planReads === 1 ? planMock : afterApplyMock).responses[0].body);
      }
      if (method === "POST" && url.pathname === "/api/alternatives") {
        const respond =
          options.alternatives ??
          ((b: { targetPeriodMonths?: number }) =>
            (b.targetPeriodMonths ? periodMock : firstMock).responses[0].body);
        const result = respond(body ?? {});
        return result instanceof Response ? result : jsonResponse(200, result);
      }
      if (method === "POST" && url.pathname === "/api/plan/apply") {
        return options.apply ? options.apply() : jsonResponse(200, { revision: 13 });
      }
      return jsonResponse(200, { messages: [], hasPending: false });
    }),
  );
  return { calls, alternativeCalls: () => calls.filter((call) => call.path === "/api/alternatives") };
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

async function card(title: string) {
  const heading = await screen.findByRole("heading", { name: title });
  return heading.closest("li") as HTMLElement;
}

describe("결과 화면 대안 카드 (#258, A-1, D-096)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("실서버 대안 API를 불러 카드 본문을 display 문자열 그대로 보여 준다", async () => {
    const server = stubServer();
    renderResult();

    const monthlyCard = await card("월 저축액을 바꾸면");
    await waitFor(() => expect(monthlyCard.textContent).toContain(monthly.display["diff.AVG.afterTaxAssetDelta"]));
    expect(monthlyCard.textContent).toContain(
      `${monthly.display["conditions.current.monthlySavingAmount"]} → ${monthly.display["conditions.changed.monthlySavingAmount"]}`,
    );
    expect(server.alternativeCalls()[0]).toEqual({ method: "POST", path: "/api/alternatives", body: {} });
  });

  it("대안 없음(hasAlternatives: false)이면 대안 영역을 그리지 않는다", async () => {
    stubServer({ alternatives: () => noneMock.responses[0].body });
    renderResult();

    await screen.findByRole("region", { name: "조건 변경 비교" });
    await waitFor(() => expect(screen.queryByRole("region", { name: "대안" })).toBeNull());
  });

  it("내 계획으로 적용은 확인창을 거쳐 P-6을 ALTERNATIVE로 부르고, 적용 뒤 대안을 다시 불러온다", async () => {
    const server = stubServer();
    renderResult();

    const monthlyCard = await card("월 저축액을 바꾸면");
    const apply = within(monthlyCard).getByRole("button", { name: "내 계획으로 적용" }) as HTMLButtonElement;
    await waitFor(() => expect(apply.disabled).toBe(false));
    fireEvent.click(apply);

    const dialog = screen.getByRole("dialog", { name: "바꾼 조건을 내 계획에 적용할까요?" });
    expect(within(dialog).getByText("월 저축액").closest("li")?.textContent).toContain(
      `${monthly.display["conditions.current.monthlySavingAmount"]} → ${monthly.display["conditions.changed.monthlySavingAmount"]}`,
    );
    fireEvent.click(within(dialog).getByRole("button", { name: "적용하기" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.queryByText("조건을 적용했어요.")).toBeNull();
    expect(server.calls).toContainEqual({
      method: "POST",
      path: "/api/plan/apply",
      body: { path: "ALTERNATIVE", expectedRevision: 12, conditionChanges: monthly.changes },
    });
    await waitFor(() => expect(server.alternativeCalls().length).toBeGreaterThanOrEqual(2));
  });

  it("대안을 받은 뒤 내 계획이 바뀌면 다시 불러와 새 revision 기준으로 다시 켠다", async () => {
    const server = stubServer();
    renderResult();
    const monthlyCard = await card("월 저축액을 바꾸면");
    const apply = () => within(monthlyCard).getByRole("button", { name: "내 계획으로 적용" }) as HTMLButtonElement;
    await waitFor(() => expect(apply().disabled).toBe(false));
    const before = server.alternativeCalls().length;

    act(() => planStore.acceptRevision(13));

    expect(apply().disabled).toBe(true);
    await waitFor(() => expect(server.alternativeCalls().length).toBe(before + 1));
    await waitFor(() => expect(apply().disabled).toBe(false));
  });
  it("대안 API가 실패하면 불러오지 못했다고 안내하고 적용 버튼을 끈다(A-1-2)", async () => {
    stubServer({ alternatives: () => problem(500, "INTERNAL_ERROR") });
    renderResult();

    const section = await screen.findByRole("region", { name: "대안" });
    await waitFor(() => expect(within(section).getByText("대안 값을 불러오지 못했어요")).toBeTruthy());
    const applies = within(section).getAllByRole("button", { name: "내 계획으로 적용" }) as HTMLButtonElement[];
    expect(applies.every((button) => button.disabled)).toBe(true);
  });

  it("대안 API가 NO_PLAN이면 입력 화면으로 간다", async () => {
    stubServer({ alternatives: () => problem(409, "NO_PLAN") });
    renderResult();

    expect(await screen.findByText("입력화면")).toBeTruthy();
  });

  it("대안 적용이 422면 대안 카드 적용만 끄고 비교 영역은 건드리지 않는다", async () => {
    stubServer({ apply: () => problem(422, "VALIDATION_FAILED") });
    renderResult();
    const monthlyCard = await card("월 저축액을 바꾸면");
    const apply = () => within(monthlyCard).getByRole("button", { name: "내 계획으로 적용" }) as HTMLButtonElement;
    await waitFor(() => expect(apply().disabled).toBe(false));

    fireEvent.click(apply());
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "적용하기" }));

    await waitFor(() => expect(screen.getByText("지금 기준으로는 적용할 수 없는 값이에요")).toBeTruthy());
    expect(apply().disabled).toBe(true);
  });
});
