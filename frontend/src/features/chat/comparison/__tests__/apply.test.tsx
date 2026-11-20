// @vitest-environment jsdom
// Issue #53 AC-3·AC-5·AC-14~AC-17: F-5 적용(C12b-7), 성공 뒤 순서(C12b-7-3), 오류별 프론트 동작(C12b-7-2).
import { act, cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { planStore as appPlanStore, setNavigate } from "../../../../api";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { INPUT_PATH, LOGIN_PATH } from "../../../../app/routes";
import { copy, doneMsg, flush, malformed, networkError, page, pendingMsg, problem, serverError, tick, userMsg } from "../../__tests__/helpers";
import {
  buttonOf,
  applyButton,
  applyButtons,
  bubble,
  comparison,
  comparisonToast,
  deferred,
  makeApi,
  makeLiveEnv,
  makeStore,
  mountChat,
  planView,
  type Reply,
} from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const APPLY_PATH = "/api/chat/messages/1031/apply";

// 되돌리기 토스트(#51)가 앱 기본 래퍼를 쓰는 경우를 위해 전역 fetch도 기록한다.
const globalFetchCalls: Array<{ method: string; url: string; body: unknown }> = [];

beforeEach(() => {
  vi.useFakeTimers();
  appPlanStore.clear();
  globalFetchCalls.length = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
      const method = init?.method ?? "GET";
      globalFetchCalls.push({ method, url, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
      if (method === "POST" && url === "/api/plan/undo") return jsonResponse(200, { revision: 14 });
      if (method === "GET" && url === "/api/plan") return jsonResponse(200, planView(14));
      return problemResponse(500, "INTERNAL_ERROR");
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  appPlanStore.clear();
});

async function mountApply(f5: Reply[], { revision = 12, planReplies = [planView(13)] as Reply[] } = {}) {
  const api = makeApi({
    F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
    "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    "F5 1031": f5,
    "POST /api/plan/undo": [{ revision: 14 }],
  });
  const env = makeStore({ initialRevision: revision, planReplies });
  await mountChat(api, env.store);
  expect(buttonOf(1031).disabled).toBe(false);
  return { api, ...env };
}

async function clickApply() {
  fireEvent.click(buttonOf(1031));
  await flush();
  await flush();
  await flush();
}

const APPLY_ERROR_KEYS = ["toast.applyFailed", "toast.applyStaleComparison", "toast.applyNotApplicable", "toast.applyPlanChanged"];

/** F-5 오류 안내가 하나도 없다: 안내 자리(chat-comparison-toast)도, 그 문구표 글자도 화면에 없다. */
function expectNoApplyErrorNotice() {
  expect(comparisonToast(), "F-5 오류 안내(chat-comparison-toast)가 있다").toBeNull();
  const text = document.body.textContent ?? "";
  for (const key of APPLY_ERROR_KEYS) expect(text, `${key} 문구가 보인다`).not.toContain(copy[key]);
}

const undoButton = () => screen.queryByRole("button", { name: "되돌리기" });
function undoButtonOf(): HTMLElement {
  const el = undoButton();
  expect(el, "되돌리기 토스트(되돌리기 버튼)가 없다").not.toBeNull();
  return el as HTMLElement;
}

describe("AC-3 마지막 카드의 적용 버튼을 누르면 (D-016, D-022, FR-AI-07)", () => {
  it("확인창 없이 바로 적용되고, 결과 화면(공유 store view)이 새 계획으로 갱신되며, 되돌리기 토스트가 보인다", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    const { api, store, planGets } = await mountApply([{ revision: 13 }]);

    await clickApply();
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(api.f5Calls()).toHaveLength(1);

    expect(planGets).toHaveLength(1);
    expect(store.getState().view?.revision).toBe(13);
    expect(undoButton()).not.toBeNull();
  });
});

describe("AC-14 적용 요청 본문과 중복 클릭 (C12b-7, C12b-7-1)", () => {
  it("본문은 {expectedRevision}이고 값은 공유 store의 revision 칸이다", async () => {
    const { api } = await mountApply([{ revision: 13 }]);
    await clickApply();
    expect(api.f5Calls()).toEqual([{ method: "POST", path: APPLY_PATH, body: { expectedRevision: 12 } }]);
  });

  it("store revision이 15로 최초 로드됐으면 expectedRevision도 15다", async () => {
    const { api } = await mountApply([{ revision: 16 }], { revision: 15, planReplies: [planView(16)] });
    await clickApply();
    expect(api.f5Calls()[0].body).toEqual({ expectedRevision: 15 });
  });

  it("경로의 messageId는 누른 카드의 메시지 id다: 최신 1033 카드를 누르면 /api/chat/messages/1033/apply로, 본문은 store revision(15)으로 보낸다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031), userMsg(1032), recalc(1033)], false)],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1033": [{ revision: 16 }],
    });
    const env = makeStore({ initialRevision: 15, planReplies: [planView(16)] });
    await mountChat(api, env.store);
    expect(applyButton(1031)?.disabled ?? true).toBe(true);
    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    await flush();
    expect(api.f5Calls()).toEqual([{ method: "POST", path: "/api/chat/messages/1033/apply", body: { expectedRevision: 15 } }]);
    expect(api.calls.filter((c) => c.path === APPLY_PATH)).toHaveLength(0);
  });

  it("expectedRevision은 클릭 순간의 공유 store revision 칸(13)이다: 결과 view(12)나 마운트 때 값(12)을 쓰지 않는다", async () => {
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1033": [{ revision: 14 }],
    });
    const env = makeStore({ planReplies: [planView(14)] });
    await mountChat(api, env.store);
    expect(buttonOf(1031).disabled).toBe(false);

    // 다른 곳의 적용·되돌리기 성공처럼 revision 칸만 13이 되고, 결과 재조회(view)는 아직 오지 않았다
    act(() => env.store.acceptRevision(13));
    await flush();
    await tick(1000); // 폴링: 1033이 RECALC_RESULT로 완료, F-4 applicable=true
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);
    expect(env.store.getState().revision).toBe(13);
    expect(env.store.getState().view?.revision).toBe(12);
    expect(env.planGets).toHaveLength(0);

    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    expect(api.f5Calls()).toEqual([{ method: "POST", path: "/api/chat/messages/1033/apply", body: { expectedRevision: 13 } }]);
  });

  it("요청 중에는 버튼이 막혀 두 번 눌러도 요청은 1번이다", async () => {
    const pending = deferred<unknown>();
    const { api } = await mountApply([pending.promise]);
    fireEvent.click(buttonOf(1031));
    await flush();
    expect(buttonOf(1031).disabled).toBe(true);
    fireEvent.click(buttonOf(1031));
    fireEvent.click(buttonOf(1031));
    await flush();
    expect(api.f5Calls()).toHaveLength(1);

    pending.resolve({ revision: 13 });
    await flush();
    await flush();
    expect(api.f5Calls()).toHaveLength(1);
  });

  it("비활성 렌더가 반영되기 전에 연속으로 들어온 클릭도 요청은 1번이다(다음 렌더의 disabled에만 기대지 않는다)", async () => {
    const pending = deferred<unknown>();
    const { api } = await mountApply([pending.promise]);
    const button = buttonOf(1031);
    // 하나의 동기 act 안에서는 클릭 사이에 다시 렌더되지 않는다. 버튼은 아직 활성인 채 클릭 세 번을 받는다.
    act(() => {
      fireEvent.click(button);
      fireEvent.click(button);
      fireEvent.click(button);
    });
    await flush();
    await flush();
    expect(api.f5Calls()).toHaveLength(1);
    expect(buttonOf(1031).disabled).toBe(true);

    pending.resolve({ revision: 13 });
    await flush();
    await flush();
    expect(api.f5Calls()).toHaveLength(1);
  });
});

describe("AC-15 적용 성공 뒤 순서 (C12b-7-3, C12a-6-5, FR-PLAN-04)", () => {
  it("① store revision을 응답 값으로 바꾼 뒤 ③ loadPlan(GET /api/plan)을 부르고, 모든 버튼이 비활성이 된다", async () => {
    const { store, planGets } = await mountApply([{ revision: 13 }]);
    await clickApply();
    expect(store.getState().revision).toBe(13);
    expect(planGets).toEqual([{ revisionAtSend: 13 }]);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("② 되돌리기 토스트는 10초 보이고 사라진다", async () => {
    await mountApply([{ revision: 13 }]);
    await clickApply();
    expect(undoButton()).not.toBeNull();
    await tick(9_900);
    expect(undoButton()).not.toBeNull();
    await tick(200);
    expect(undoButton()).toBeNull();
  });

  it("② 토스트가 들고 있는 값은 응답 revision(13)이다: 되돌리기를 누르면 expectedRevision 13으로 보낸다", async () => {
    const { api } = await mountApply([{ revision: 13 }]);
    await clickApply();
    fireEvent.click(undoButtonOf());
    await flush();
    await flush();
    const undoBodies = [
      ...api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/undo").map((c) => c.body),
      ...globalFetchCalls.filter((c) => c.method === "POST" && c.url === "/api/plan/undo").map((c) => c.body),
    ];
    expect(undoBodies).toEqual([{ expectedRevision: 13 }]);
  });

  it("② 되돌리기 토스트는 ③ 결과 재조회(GET /api/plan) 응답을 기다리지 않고 뜨며, 10초는 적용 성공 시점부터 센다", async () => {
    const get = deferred<unknown>();
    const { planGets } = await mountApply([{ revision: 13 }], { planReplies: [() => get.promise] });
    await clickApply();
    expect(planGets).toEqual([{ revisionAtSend: 13 }]);
    expect(undoButton()).not.toBeNull(); // GET은 아직 응답 전
    await tick(9_900);
    expect(undoButton()).not.toBeNull();
    await tick(200);
    expect(undoButton()).toBeNull();
    get.resolve(planView(13));
    await flush();
    expect(undoButton()).toBeNull();
  });

  it("② 토스트가 열린 동안 공유 store의 revision 칸이 토스트 값(13)과 달라지면 10초 전이라도 바로 닫힌다", async () => {
    // 앱에서 대화창의 deps.planStore는 앱 공유 store 하나다. 그 store를 그대로 넣어 토스트가 같은 칸을 따르는지 본다.
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        if ((init?.method ?? "GET") === "GET" && url === "/api/plan") return jsonResponse(200, planView(13));
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
    });
    await mountChat(api, appPlanStore);
    expect(buttonOf(1031).disabled).toBe(false);
    await clickApply();
    expect(appPlanStore.getState().revision).toBe(13);
    expect(undoButton()).not.toBeNull();

    await tick(1000);
    act(() => appPlanStore.acceptRevision(14)); // 다른 곳(입력 저장 등)에서 계획이 바뀜
    await flush();
    expect(undoButton()).toBeNull();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("③ revision이 바뀌기 전(12)에 떠난 GET에는 합류하지 않고 새 revision(13)에서 새로 부른다(single-flight, C12a-6-5)", async () => {
    const early = deferred<unknown>();
    const { store, planGets } = await mountApply([{ revision: 13 }], {
      planReplies: [() => early.promise, planView(13)],
    });
    const other = store.loadPlan(); // 다른 화면이 revision 12에서 보낸 GET(진행 중)
    await flush();
    await clickApply();
    expect(planGets).toEqual([{ revisionAtSend: 12 }, { revisionAtSend: 13 }]);
    expect(store.getState().view?.revision).toBe(13);

    early.resolve(planView(12));
    await other;
    await flush();
    expect(store.getState().revision).toBe(13);
    expect(store.getState().view?.revision).toBe(13);
  });

  it("③ loadPlan은 공유 store의 함수를 F-5 성공 뒤 한 번, fresh 없이 부르고, 부를 때 store revision은 이미 13이다", async () => {
    const env = makeStore();
    const original = env.store.loadPlan.bind(env.store);
    const revisionAtCall: Array<number | null> = [];
    const loadPlan = vi.spyOn(env.store, "loadPlan").mockImplementation((options) => {
      revisionAtCall.push(env.store.getState().revision);
      return original(options);
    });
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [{ revision: 14 }],
    });
    await mountChat(api, env.store);
    expect(loadPlan).not.toHaveBeenCalled();
    await clickApply();
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(revisionAtCall).toEqual([13]);
    expect((loadPlan.mock.calls[0][0] as { fresh?: boolean } | undefined)?.fresh).not.toBe(true);
    expect(env.planGets).toEqual([{ revisionAtSend: 13 }]);
  });

  it("② 되돌리기 토스트가 뜬 뒤 ③ 재조회가 다른 revision(14)을 가져오면 토스트는 들고 있는 값(13)과 달라져 10초 전에 닫힌다", async () => {
    // beforeEach의 전역 fetch는 GET /api/plan에 revision 14를 준다(그 사이 다른 곳에서 계획이 바뀐 경우).
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
    });
    await mountChat(api, appPlanStore);
    expect(buttonOf(1031).disabled).toBe(false);
    await clickApply();
    await flush();
    expect(globalFetchCalls.filter((c) => c.method === "GET" && c.url === "/api/plan")).toHaveLength(1);
    expect(appPlanStore.getState().revision).toBe(14);
    expect(undoButton()).toBeNull();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("② 되돌리기가 500이면 #51 되돌리기 토스트와 같이 토스트가 남고 '되돌리지 못했어요'가 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        globalFetchCalls.push({ method, url, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
        if (method === "GET" && url === "/api/plan") return jsonResponse(200, planView(13));
        return problemResponse(500, "INTERNAL_ERROR"); // POST /api/plan/undo 포함
      }),
    );
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [serverError()],
    });
    await mountChat(api, appPlanStore);
    await clickApply();
    expect(appPlanStore.getState().revision).toBe(13);

    await tick(1000);
    fireEvent.click(undoButtonOf());
    await flush();
    await flush();
    await flush();
    const undoBodies = [
      ...api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/undo").map((c) => c.body),
      ...globalFetchCalls.filter((c) => c.method === "POST" && c.url === "/api/plan/undo").map((c) => c.body),
    ];
    expect(undoBodies).toEqual([{ expectedRevision: 13 }]);
    expect(undoButton()).not.toBeNull();
    expect(screen.getByText(copy["undo.failed"])).not.toBeNull();
    expect(appPlanStore.getState().revision).toBe(13);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("② 되돌리기가 409 NOTHING_TO_UNDO면 #51 되돌리기 토스트와 같이 토스트가 닫히고 '이미 되돌렸어요'가 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        globalFetchCalls.push({ method, url, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
        if (method === "POST" && url === "/api/plan/undo") return problemResponse(409, "NOTHING_TO_UNDO");
        if (method === "GET" && url === "/api/plan") return jsonResponse(200, planView(13));
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [problem(409, "NOTHING_TO_UNDO")],
      "GET /api/plan": [planView(13)],
    });
    await mountChat(api, appPlanStore);
    await clickApply();
    expect(appPlanStore.getState().revision).toBe(13);

    await tick(1000);
    fireEvent.click(undoButtonOf());
    await flush();
    await flush();
    await flush();
    const undoBodies = [
      ...api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/undo").map((c) => c.body),
      ...globalFetchCalls.filter((c) => c.method === "POST" && c.url === "/api/plan/undo").map((c) => c.body),
    ];
    expect(undoBodies).toEqual([{ expectedRevision: 13 }]);
    expect(undoButton(), "NOTHING_TO_UNDO 뒤에도 되돌리기 토스트가 남아 있다").toBeNull();
    expect(screen.getByText(copy["undo.nothingToUndo"])).not.toBeNull();
    expect(screen.queryByText(copy["undo.failed"])).toBeNull();
    expect(appPlanStore.getState().revision).toBe(13);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("② 되돌리기가 409 NOTHING_TO_UNDO면 토스트를 닫고 안내한 뒤 공유 store로 현재 계획을 다시 불러온다(C12a-6-5)", async () => {
    let planGetCount = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        globalFetchCalls.push({ method, url, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
        if (method === "POST" && url === "/api/plan/undo") return problemResponse(409, "NOTHING_TO_UNDO");
        if (method === "GET" && url === "/api/plan") {
          planGetCount += 1;
          // 첫 GET은 적용 직후 재조회(13), 둘째는 NOTHING_TO_UNDO 뒤 재조회: 그 사이 다른 곳에서 바뀐 현재 계획(14)
          return jsonResponse(200, planView(planGetCount === 1 ? 13 : 14));
        }
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [problem(409, "NOTHING_TO_UNDO")],
    });
    await mountChat(api, appPlanStore);
    await clickApply();
    await flush();
    const planGetsOf = () => globalFetchCalls.filter((c) => c.method === "GET" && c.url === "/api/plan");
    expect(planGetsOf()).toHaveLength(1); // 적용 직후 재조회는 끝났다
    expect(appPlanStore.getState().view?.revision).toBe(13);

    await tick(1000);
    fireEvent.click(undoButtonOf());
    await flush();
    await flush();
    await flush();
    await flush();
    const undoBodies = [
      ...api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/undo").map((c) => c.body),
      ...globalFetchCalls.filter((c) => c.method === "POST" && c.url === "/api/plan/undo").map((c) => c.body),
    ];
    expect(undoBodies).toEqual([{ expectedRevision: 13 }]);
    expect(planGetsOf(), "NOTHING_TO_UNDO 뒤 계획을 다시 불러오지 않았다").toHaveLength(2);
    expect(undoButton()).toBeNull();
    expect(screen.getByText(copy["undo.nothingToUndo"])).not.toBeNull();
    expect(appPlanStore.getState().revision).toBe(14);
    expect(appPlanStore.getState().view?.revision).toBe(14);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("③ 적용 성공 뒤 결과 재조회(GET /api/plan)만 500으로 실패해도 적용 성공 상태가 유지된다: revision 13·모든 버튼 비활성·되돌리기 토스트 10초, 적용 실패 안내·추가 F-5 없음", async () => {
    const get = deferred<unknown>();
    const { api, store, planGets } = await mountApply([{ revision: 13 }, { revision: 99 }], { planReplies: [() => get.promise] });
    await clickApply();
    expect(store.getState().revision).toBe(13);
    expect(planGets).toEqual([{ revisionAtSend: 13 }]);
    expect(undoButton()).not.toBeNull();

    get.reject(serverError()); // 재조회만 실패
    await flush();
    await flush();
    expect(store.getState().revision).toBe(13);
    expect(store.getState().view?.revision).toBe(12);
    expectNoApplyErrorNotice();
    expect(undoButton(), "재조회 실패로 되돌리기 토스트가 닫혔다").not.toBeNull();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(api.f5Calls()).toHaveLength(1);

    await tick(9_900); // 토스트는 적용 성공 시점부터 10초
    expect(undoButton()).not.toBeNull();
    await tick(200);
    expect(undoButton()).toBeNull();
    expectNoApplyErrorNotice();
    expect(api.f5Calls()).toHaveLength(1);
    expect(planGets).toHaveLength(1); // 자동으로 다시 부르지 않는다
    expect(store.getState().revision).toBe(13);
  });

  it("①→②→③ 순서를 기록한다: revision 13 반영 다음에 loadPlan이 시작되고, loadPlan을 부르는 순간 되돌리기 토스트는 이미 요청돼 있다(③ 시작이 동기적으로 실패해도 ②의 토스트가 보이고 적용 성공 상태가 유지된다)", async () => {
    const env = makeStore();
    const order: string[] = [];
    env.store.subscribe((state, prev) => {
      if (state.revision !== prev.revision) order.push(`revision ${state.revision}`);
    });
    // ③ 시작 순간을 기록하고 그 자리에서 동기적으로 실패시킨다. ②가 ③ 뒤에 있으면 토스트 요청까지 가지 못한다.
    const loadPlan = vi.spyOn(env.store, "loadPlan").mockImplementation(() => {
      order.push("loadPlan");
      throw new Error("재조회 시작 실패(동기)");
    });
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [{ revision: 14 }],
    });
    await mountChat(api, env.store);
    expect(buttonOf(1031).disabled).toBe(false);
    await clickApply();

    expect(order).toEqual(["revision 13", "loadPlan"]);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(undoButton(), "③(loadPlan)이 ②(되돌리기 토스트 요청)보다 먼저 일어났다").not.toBeNull();
    expect(env.store.getState().revision).toBe(13);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expectNoApplyErrorNotice();
    expect(api.f5Calls()).toHaveLength(1);
    await tick(9_900);
    expect(undoButton()).not.toBeNull();
    await tick(200);
    expect(undoButton()).toBeNull();
  });

  it("F-5 성공 응답(13)이 늦게 오는 사이 store가 더 최신 계획(14)을 받았으면, store는 14를 유지하고 낡은 revision(13)의 되돌리기 토스트를 보이지 않는다(같은 revision 재조회 뒤에도)", async () => {
    const pending = deferred<unknown>();
    const { store } = await mountApply([pending.promise], { planReplies: [planView(14)] });
    fireEvent.click(buttonOf(1031));
    await flush();

    act(() => store.acceptPlanView(planView(14))); // 다른 곳의 변경이 먼저 반영됨
    await flush();
    expect(store.getState().revision).toBe(14);

    pending.resolve({ revision: 13 }); // 서버에서는 성공했지만 응답이 늦었다
    await flush();
    await flush();
    await flush();
    expect(store.getState().revision).toBe(14);
    expect(undoButton(), "현재 계획(14)과 다른 revision(13)의 되돌리기 토스트가 떴다").toBeNull();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expectNoApplyErrorNotice();

    await tick(1000); // 재조회가 같은 revision 14를 돌려줘도
    await flush();
    await flush();
    expect(store.getState().revision).toBe(14);
    expect(store.getState().view?.revision).toBe(14);
    expect(undoButton()).toBeNull();
    await tick(10_000);
    expect(undoButton()).toBeNull();
  });

  it("② 대화에서 띄운 되돌리기 토스트의 되돌리기가 409 PLAN_CHANGED면 #51 토스트와 같이 닫히고 '계획이 다른 곳에서 바뀌었어요' 안내 뒤 공유 store로 현재 계획을 다시 불러온다", async () => {
    let planGetCount = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        globalFetchCalls.push({ method, url, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
        if (method === "POST" && url === "/api/plan/undo") return problemResponse(409, "PLAN_CHANGED");
        if (method === "GET" && url === "/api/plan") {
          planGetCount += 1;
          // 첫 GET은 적용 직후 재조회(13), 둘째는 PLAN_CHANGED 뒤 재조회: 다른 곳에서 바뀐 현재 계획(15)
          return jsonResponse(200, planView(planGetCount === 1 ? 13 : 15));
        }
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    appPlanStore.acceptPlanView(planView(12));
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
      "POST /api/plan/undo": [problem(409, "PLAN_CHANGED")],
    });
    await mountChat(api, appPlanStore);
    await clickApply();
    await flush();
    const planGetsOf = () => globalFetchCalls.filter((c) => c.method === "GET" && c.url === "/api/plan");
    expect(planGetsOf()).toHaveLength(1);
    expect(appPlanStore.getState().view?.revision).toBe(13);

    await tick(1000);
    fireEvent.click(undoButtonOf());
    await flush();
    await flush();
    await flush();
    await flush();
    const undoBodies = [
      ...api.calls.filter((c) => c.method === "POST" && c.path === "/api/plan/undo").map((c) => c.body),
      ...globalFetchCalls.filter((c) => c.method === "POST" && c.url === "/api/plan/undo").map((c) => c.body),
    ];
    expect(undoBodies).toEqual([{ expectedRevision: 13 }]);
    expect(undoButton(), "PLAN_CHANGED 뒤에도 되돌리기 토스트가 남아 있다").toBeNull();
    expect(screen.getByText(copy["plan.changedElsewhere"])).not.toBeNull();
    expect(screen.queryByText(copy["undo.failed"])).toBeNull();
    expect(planGetsOf(), "PLAN_CHANGED 뒤 계획을 다시 불러오지 않았다").toHaveLength(2);
    expect(appPlanStore.getState().revision).toBe(15);
    expect(appPlanStore.getState().view?.revision).toBe(15);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("② 대화에서 띄운 되돌리기 토스트의 되돌리기가 409 NO_PLAN이면 #51 토스트와 같이 닫히고 토스트 revision(13)으로 store를 비워 입력 화면으로 간다(대화창 메시지는 그대로)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
        const method = init?.method ?? "GET";
        globalFetchCalls.push({ method, url, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
        if (method === "POST" && url === "/api/plan/undo") return problemResponse(409, "NO_PLAN");
        if (method === "GET" && url === "/api/plan") return jsonResponse(200, planView(13));
        return problemResponse(500, "INTERNAL_ERROR");
      }),
    );
    const navigations: string[] = [];
    setNavigate((to) => navigations.push(to));
    try {
      appPlanStore.acceptPlanView(planView(12));
      const acceptNoPlan = vi.spyOn(appPlanStore, "acceptNoPlan");
      const api = makeApi({
        F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
        "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
        "F5 1031": [{ revision: 13 }],
        "POST /api/plan/undo": [problem(409, "NO_PLAN")],
      });
      await mountChat(api, appPlanStore);
      await clickApply();
      await flush();
      expect(appPlanStore.getState().revision).toBe(13);

      await tick(1000);
      fireEvent.click(undoButtonOf());
      await flush();
      await flush();
      await flush();
      expect(undoButton(), "NO_PLAN 뒤에도 되돌리기 토스트가 남아 있다").toBeNull();
      expect(acceptNoPlan).toHaveBeenCalledTimes(1);
      expect(acceptNoPlan).toHaveBeenCalledWith(13);
      expect(appPlanStore.getState().revision).toBeNull();
      expect(appPlanStore.getState().view).toBeNull();
      expect(navigations).toContain(INPUT_PATH);
      expect(screen.queryByText(copy["undo.failed"])).toBeNull();
      expect(bubble(1029)).not.toBeNull();
      expect(bubble(1031)).not.toBeNull();
      expect(screen.getByTestId("chat-window")).not.toBeNull();
    } finally {
      setNavigate(null);
    }
  });

  it("④ 이 Issue는 F-3(POST /api/chat/explanations)을 부르지 않는다", async () => {
    const { api } = await mountApply([{ revision: 13 }]);
    await clickApply();
    await tick(5000);
    expect(api.calls.filter((c) => c.path.startsWith("/api/chat/explanations"))).toHaveLength(0);
    expect(globalFetchCalls.filter((c) => c.url.startsWith("/api/chat/explanations"))).toHaveLength(0);
  });
});

/**
 * 1031 적용 요청 중에 폴링으로 1033이 도착하고 F-4 applicable=true로 활성이 된 뒤, 1031의 F-5가 error로 늦게 실패한다.
 * 끄는 것은 요청을 보낸 '그 버튼'(1031)이고 응답 시점의 최신 카드(1033)가 아니다(C12b-7-2).
 */
async function expectLateErrorDisablesOnlyRequestedButton(error: Error, toastKey: string | null) {
  const pending = deferred<unknown>();
  const api = makeApi({
    F2: [
      page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
      page([recalc(1033)], false),
    ],
    "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    "F5 1031": [pending.promise],
  });
  const { store, planGets } = makeStore();
  await mountChat(api, store);
  fireEvent.click(buttonOf(1031));
  await flush();
  await tick(1000); // 폴링: 1033 RECALC_RESULT 도착, F-4 applicable=true
  await flush();
  await flush();
  expect(buttonOf(1033).disabled).toBe(false);

  pending.reject(error);
  await flush();
  await flush();
  if (toastKey) expect(comparisonToast()?.textContent).toContain(copy[toastKey]);
  else expectNoApplyErrorNotice();
  expect(applyButton(1031)?.disabled ?? true).toBe(true);
  expect(buttonOf(1033).disabled, "늦게 온 1031의 오류가 최신 1033 버튼을 껐다").toBe(false);
  expect(
    applyButtons()
      .filter((button) => !button.disabled)
      .map((button) => button.closest("[data-message-id]")?.getAttribute("data-message-id")),
  ).toEqual(["1033"]);
  expect(api.f5Calls()).toHaveLength(1);
  expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
  expect(planGets).toHaveLength(0);
  expect(store.getState().revision).toBe(12);
  expect(undoButton()).toBeNull();
}

describe("AC-5 적용 응답이 409 STALE_COMPARISON이면 (C12b-7-2)", () => {
  it("그 버튼이 비활성이 되고 '가장 최근 비교 결과만 적용할 수 있어요' 토스트가 보이며 계획을 다시 부르지 않는다", async () => {
    const { store, planGets } = await mountApply([problem(409, "STALE_COMPARISON")]);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(planGets).toHaveLength(0);
    expect(store.getState().revision).toBe(12);
    expect(undoButton()).toBeNull();
  });

  it("이전 카드(1031)의 STALE_COMPARISON이 새 비교(1033)의 활성 뒤에 늦게 와도 안내를 보이고 1031만 비활성, 1033은 활성 그대로다", async () => {
    await expectLateErrorDisablesOnlyRequestedButton(problem(409, "STALE_COMPARISON"), "toast.applyStaleComparison");
  });
});

describe("AC-16 F-5 오류별 프론트 동작 (C12b-7-2, C12a-6-7)", () => {
  it("PLAN_CHANGED: '계획이 다른 곳에서 바뀌었어요' 안내 뒤 loadPlan, 새 revision(14)을 받으면 모든 버튼이 비활성", async () => {
    const { store, planGets } = await mountApply([problem(409, "PLAN_CHANGED")], { planReplies: [planView(14)] });
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(planGets).toHaveLength(1);
    expect(store.getState().revision).toBe(14);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(undoButton()).toBeNull();
  });

  it("PLAN_CHANGED 안내는 재조회 응답을 기다리지 않고 먼저 보이고, 그 뒤 loadPlan의 새 revision(14)으로 모든 버튼이 꺼진다", async () => {
    const get = deferred<unknown>();
    const { store, planGets } = await mountApply([problem(409, "PLAN_CHANGED")], { planReplies: [() => get.promise] });
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(planGets).toEqual([{ revisionAtSend: 12 }]);
    expect(store.getState().revision).toBe(12);

    get.resolve(planView(14));
    await flush();
    await flush();
    expect(store.getState().revision).toBe(14);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(planGets).toHaveLength(1);
  });

  it("PLAN_CHANGED 때 같은 revision(12)에서 떠난 GET이 진행 중이면 새로 보내지 않고 합류한다(single-flight, fresh 없음)", async () => {
    const inflight = deferred<unknown>();
    const { store, planGets } = await mountApply([problem(409, "PLAN_CHANGED")], {
      planReplies: [() => inflight.promise, planView(15)],
    });
    const other = store.loadPlan(); // 다른 화면이 revision 12에서 보낸 GET(진행 중)
    await flush();
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(planGets).toEqual([{ revisionAtSend: 12 }]);

    inflight.resolve(planView(14));
    await other;
    await flush();
    await flush();
    expect(planGets).toHaveLength(1);
    expect(store.getState().revision).toBe(14);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("PLAN_CHANGED 때 대화창은 공유 store의 loadPlan을 fresh 없이 부르고, store가 진행 중인 같은 revision GET에 합류시킨다(GET 1건)", async () => {
    const inflight = deferred<unknown>();
    const env = makeStore({ planReplies: [() => inflight.promise, planView(15)] });
    const loadPlan = vi.spyOn(env.store, "loadPlan");
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [problem(409, "PLAN_CHANGED")],
    });
    await mountChat(api, env.store);
    const other = env.store.loadPlan(); // 다른 화면이 revision 12에서 보낸 GET(진행 중)
    await flush();
    expect(loadPlan).toHaveBeenCalledTimes(1);

    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(loadPlan).toHaveBeenCalledTimes(2); // 대화창이 부른 loadPlan
    expect((loadPlan.mock.calls[1][0] as { fresh?: boolean } | undefined)?.fresh).not.toBe(true);
    expect(env.planGets).toEqual([{ revisionAtSend: 12 }]);

    inflight.resolve(planView(14));
    await other;
    await flush();
    await flush();
    expect(env.planGets).toHaveLength(1);
    expect(env.store.getState().revision).toBe(14);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
  });

  it("PLAN_CHANGED 때 합류한 진행 중 GET이 같은 revision(12)을 돌려주면 revision이 바뀌지 않았으므로 버튼을 끄지 않는다: 안내는 보이고 GET 1건, 1031은 기존 활성 조건대로 활성이라 다시 누를 수 있다(잠정 결정 P-57)", async () => {
    const inflight = deferred<unknown>();
    const { api, store, planGets } = await mountApply([problem(409, "PLAN_CHANGED"), { revision: 13 }], {
      planReplies: [() => inflight.promise, planView(13)],
    });
    const other = store.loadPlan(); // 다른 화면이 revision 12에서 보낸 GET(진행 중, 서버 쓰기 전에 조회됨)
    await flush();
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(planGets).toEqual([{ revisionAtSend: 12 }]);

    inflight.resolve(planView(12)); // 쓰기 전에 조회된 같은 revision
    await other;
    await flush();
    await flush();
    expect(planGets).toHaveLength(1);
    expect(store.getState().revision).toBe(12);
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyPlanChanged"]);
    expect(buttonOf(1031).disabled, "revision이 그대로인데 PLAN_CHANGED만으로 버튼을 영구히 껐다").toBe(false);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);

    await clickApply(); // 다시 시도
    expect(api.f5Calls()).toHaveLength(2);
    expect(api.f5Calls()[1].body).toEqual({ expectedRevision: 12 });
    expect(store.getState().revision).toBe(13);
  });

  it.each([
    ["VALIDATION_FAILED", 422],
    ["FIELD_NOT_CHANGEABLE", 422],
  ])("%s: '지금 기준으로는 적용할 수 없는 값이에요' + 버튼 비활성, 계획은 그대로", async (code, status) => {
    const { store, planGets } = await mountApply([problem(status, code, [{ field: "monthlySavingAmount", reason: "OUT_OF_RANGE" }])]);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyNotApplicable"]);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(planGets).toHaveLength(0);
    expect(store.getState().revision).toBe(12);
  });

  // 422로 끄는 것은 요청을 보낸 '그 버튼'이다(C12b-7-2). 응답 시점의 최신 카드가 아니다.
  async function expectOnlyRequestedButtonDisabledAfterLate422(code: string) {
    const pending = deferred<unknown>();
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1031": [pending.promise],
    });
    const { store, planGets } = makeStore();
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착, F-4 applicable=true
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);

    pending.reject(problem(422, code, [{ field: "monthlySavingAmount", reason: "OUT_OF_RANGE" }]));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyNotApplicable"]);
    expect(applyButton(1031)?.disabled ?? true).toBe(true);
    expect(buttonOf(1033).disabled, `늦게 온 1031의 ${code}가 최신 1033 버튼을 껐다`).toBe(false);
    expect(
      applyButtons()
        .filter((button) => !button.disabled)
        .map((button) => button.closest("[data-message-id]")?.getAttribute("data-message-id")),
    ).toEqual(["1033"]);
    expect(api.f5Calls()).toHaveLength(1);
    expect(planGets).toHaveLength(0);
    expect(store.getState().revision).toBe(12);
  }

  it("VALIDATION_FAILED가 1031 요청 중 도착한 새 비교(1033)의 활성 뒤에 늦게 와도 안내를 보이고 1031만 비활성, 1033은 활성 그대로다", async () => {
    await expectOnlyRequestedButtonDisabledAfterLate422("VALIDATION_FAILED");
  });

  it("FIELD_NOT_CHANGEABLE이 1031 요청 중 도착한 새 비교(1033)의 활성 뒤에 늦게 와도 안내를 보이고 1031만 비활성, 1033은 활성 그대로다", async () => {
    await expectOnlyRequestedButtonDisabledAfterLate422("FIELD_NOT_CHANGEABLE");
  });

  it("NO_PLAN: acceptNoPlan(보낸 expectedRevision)으로 store를 비우고 입력 화면으로, 대화창 메시지 목록은 그대로", async () => {
    const { store, navigations } = await mountApply([problem(409, "NO_PLAN")]);
    await clickApply();
    expect(store.getState().revision).toBeNull();
    expect(store.getState().view).toBeNull();
    expect(navigations).toContain(INPUT_PATH);
    expect(bubble(1029)).not.toBeNull();
    expect(bubble(1031)).not.toBeNull();
    expect(screen.getByTestId("chat-window")).not.toBeNull();
  });

  it("NO_PLAN 뒤 같은 대화창에서 새 계획이 처음 채워져도(null -> 1) 이전 비교 1031 버튼은 비활성으로 남고 F-4를 다시 부르지 않는다", async () => {
    const { api, store } = await mountApply([problem(409, "NO_PLAN")]);
    await clickApply();
    expect(store.getState().revision).toBeNull();

    act(() => store.acceptPlanView(planView(1)));
    await flush();
    await tick(1000);
    await flush();
    expect(store.getState().revision).toBe(1);
    expect(buttonOf(1031).disabled).toBe(true);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(api.f5Calls()).toHaveLength(1);
  });

  it("NO_PLAN: 응답 전에 store revision이 바뀌었어도 acceptNoPlan에는 요청에 보낸 expectedRevision(12)을 넘긴다", async () => {
    const pending = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [pending.promise],
    });
    const env = makeStore();
    const acceptNoPlan = vi.spyOn(env.store, "acceptNoPlan");
    await mountChat(api, env.store);
    fireEvent.click(buttonOf(1031));
    await flush();
    expect(api.f5Calls()[0]?.body).toEqual({ expectedRevision: 12 });

    act(() => env.store.acceptRevision(13)); // 요청 중에 다른 곳에서 계획이 바뀜
    await flush();
    pending.reject(problem(409, "NO_PLAN"));
    await flush();
    await flush();
    expect(acceptNoPlan).toHaveBeenCalledTimes(1);
    expect(acceptNoPlan).toHaveBeenCalledWith(12);
    expect(env.store.getState().revision).toBeNull();
    expect(env.navigations).toContain(INPUT_PATH);
    expect(bubble(1031)).not.toBeNull();
  });

  it("NOT_FOUND: 버튼 비활성, 계획은 그대로", async () => {
    const { store, planGets } = await mountApply([problem(404, "NOT_FOUND")]);
    await clickApply();
    expect(buttonOf(1031).disabled).toBe(true);
    expect(planGets).toHaveLength(0);
    expect(store.getState().revision).toBe(12);
  });

  it("NOT_FOUND에는 안내 토스트가 없다(계약에 문구가 없다): chat-comparison-toast도, F-5 오류 문구도 보이지 않는다", async () => {
    await mountApply([problem(404, "NOT_FOUND")]);
    await clickApply();
    expect(buttonOf(1031).disabled).toBe(true);
    expectNoApplyErrorNotice();
    await tick(5000);
    expectNoApplyErrorNotice();
  });

  it("이전 카드(1031)의 NOT_FOUND가 새 비교(1033)의 활성 뒤에 늦게 와도 1031만 비활성, 1033은 활성 그대로다", async () => {
    await expectLateErrorDisablesOnlyRequestedButton(problem(404, "NOT_FOUND"), null);
  });

  it("UNAUTHENTICATED: 로그인 화면으로 간다(래퍼 #13)", async () => {
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") {
        return jsonResponse(200, page([userMsg(1030), recalc(1031)], false));
      }
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") {
        return jsonResponse(200, comparison("get-comparison.200.json"));
      }
      if (method === "POST" && requestPath === APPLY_PATH) return problemResponse(401, "UNAUTHENTICATED");
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    await mountChat(env.api, env.store);
    await tick(0);
    expect(buttonOf(1031).disabled).toBe(false);
    await clickApply();
    await tick(0);
    expect(env.navigations).toContain(LOGIN_PATH);
  });
});

describe("AC-17 500·네트워크 실패·MALFORMED_REQUEST면 재시도할 수 있다 (C12b-7-2)", () => {
  it.each([
    ["500 INTERNAL_ERROR", serverError],
    ["네트워크 실패", networkError],
    ["400 MALFORMED_REQUEST", malformed],
  ])("%s: '적용하지 못했어요' 토스트, 버튼은 활성 그대로이고 다시 누르면 다시 보낸다", async (_name, makeError) => {
    const { api, store, planGets } = await mountApply([makeError(), { revision: 13 }]);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    expect(buttonOf(1031).disabled).toBe(false);
    expect(planGets).toHaveLength(0);
    expect(store.getState().revision).toBe(12);

    await clickApply();
    expect(api.f5Calls()).toHaveLength(2);
    expect(store.getState().revision).toBe(13);
  });

  it("200인데 본문에 유효한 정수 revision이 없으면({}·null·문자열 \"13\"·null 값·13.5) 성공 처리를 하지 않고 네트워크 실패와 같다: '적용하지 못했어요', 버튼 활성, revision 12, 되돌리기 토스트·재조회 없음, 다시 누르면 성공한다(진짜 래퍼, 잠정 결정 P-58)", async () => {
    const invalidBodies: Array<[string, unknown]> = [
      ["빈 객체 {}", {}],
      ["null", null],
      ['문자열 "13"', { revision: "13" }],
      ["revision null", { revision: null }],
      ["정수 아님 13.5", { revision: 13.5 }],
    ];
    for (const [name, invalid] of invalidBodies) {
      let applyCount = 0;
      const env = makeLiveEnv((method, requestPath) => {
        if (method === "GET" && requestPath === "/api/chat/messages") {
          return jsonResponse(200, page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false));
        }
        if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") {
          return jsonResponse(200, comparison("get-comparison.200.json", { messageId: 1031 }));
        }
        if (method === "POST" && requestPath === APPLY_PATH) {
          applyCount += 1;
          return jsonResponse(200, applyCount === 1 ? invalid : { revision: 13 });
        }
        if (method === "GET" && requestPath === "/api/plan") return jsonResponse(200, planView(13));
        throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
      });
      const planFetches = () => env.fetchStub.mock.calls.filter(([input]) => String(input) === "/api/plan").length;
      await mountChat(env.api, env.store);
      await tick(0);
      expect(buttonOf(1031).disabled).toBe(false);

      await clickApply();
      await tick(0);
      expect(comparisonToast()?.textContent, `${name}: '적용하지 못했어요'가 없다`).toContain(copy["toast.applyFailed"]);
      expect(buttonOf(1031).disabled, `${name}: 버튼이 활성 그대로가 아니다`).toBe(false);
      expect(env.store.getState().revision, `${name}: store revision이 바뀌었다`).toBe(12);
      expect(undoButton(), `${name}: 되돌리기 토스트가 떴다`).toBeNull();
      expect(planFetches(), `${name}: 결과 재조회를 했다`).toBe(0);
      expect(env.navigations).toEqual([]);

      await clickApply(); // 다시 시도
      await tick(0);
      expect(applyCount).toBe(2);
      expect(env.store.getState().revision).toBe(13);
      expect(undoButton()).not.toBeNull();
      cleanup();
    }
  });

  // 재시도 가능 오류도 활성 조건(C12b-6 "버튼 활성 한 줄")을 넘지 않는다: revision이 바뀐 뒤면 그 메시지는 이미 있던 것이다.
  async function expectStaysDisabledAfterLateError(error: unknown) {
    const pending = deferred<unknown>();
    const { store, planGets } = await mountApply([pending.promise]);
    fireEvent.click(buttonOf(1031));
    await flush();
    act(() => store.acceptPlanView(planView(13))); // 요청 중에 입력 수정 저장(P-3 200)
    await flush();
    pending.reject(error);
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    expect(buttonOf(1031).disabled).toBe(true);
    expect(store.getState().revision).toBe(13);
    expect(planGets).toHaveLength(0);
  }

  // 재시도 가능 오류도 '최대 RECALC_RESULT id' 조건을 넘지 않는다: 요청 중에 새 비교가 오면 그 버튼은 이전 카드다.
  async function expectOlderStaysDisabledAfterNewComparison(error: unknown) {
    const pending = deferred<unknown>();
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
      "F5 1031": [pending.promise],
    });
    const { store, planGets } = makeStore();
    await mountChat(api, store);
    fireEvent.click(buttonOf(1031));
    await flush();
    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착
    await flush();
    await flush();
    expect(buttonOf(1033).disabled).toBe(false);

    pending.reject(error); // 이전(1031) 적용 요청이 늦게 실패
    await flush();
    await flush();
    expect(applyButton(1031)?.disabled ?? true, "1031 버튼이 다시 활성이 됐다").toBe(true);
    expect(applyButtons().filter((button) => !button.disabled).map((button) => button.closest("[data-message-id]")?.getAttribute("data-message-id"))).toEqual([
      "1033",
    ]);
    expect(api.f5Calls()).toHaveLength(1);
    expect(store.getState().revision).toBe(12);
    expect(planGets).toHaveLength(0);
  }

  it("요청 중 새 비교(1033)가 도착하고 F-4가 applicable=true로 온 뒤 1031 적용이 500으로 실패해도 1031은 비활성, 활성 버튼은 1033뿐이다", async () => {
    await expectOlderStaysDisabledAfterNewComparison(serverError());
  });

  it("요청 중 새 비교(1033)가 도착한 뒤 1031 적용이 네트워크 실패여도 1031은 비활성, 활성 버튼은 1033뿐이다", async () => {
    await expectOlderStaysDisabledAfterNewComparison(networkError());
  });

  it("요청 중 revision이 바뀐 뒤 500 INTERNAL_ERROR가 오면 안내는 보이지만 버튼은 비활성으로 남는다", async () => {
    await expectStaysDisabledAfterLateError(serverError());
  });

  it("요청 중 revision이 바뀐 뒤 네트워크 실패가 오면 안내는 보이지만 버튼은 비활성으로 남는다", async () => {
    await expectStaysDisabledAfterLateError(networkError());
  });

  it("요청 중 revision이 바뀐 뒤 400 MALFORMED_REQUEST가 오면 안내는 보이지만 버튼은 비활성으로 남는다", async () => {
    await expectStaysDisabledAfterLateError(malformed());
  });
});

describe("AC-25 F-5 오류 토스트는 다음 적용 시도 또는 새 비교 도착 때까지 유지한다 (사용자 결정 U-5=A)", () => {
  it("'적용하지 못했어요'는 시간이 지나도 남아 있고, 다시 누르면(다음 적용 시도) 사라진다", async () => {
    const retry = deferred<unknown>();
    await mountApply([serverError(), retry.promise]);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    await tick(60_000);
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);

    fireEvent.click(buttonOf(1031));
    await flush();
    expect(comparisonToast()).toBeNull();
    retry.resolve({ revision: 13 });
    await flush();
    await flush();
  });

  it("'가장 최근 비교 결과만 적용할 수 있어요'는 새 RECALC_RESULT가 폴링으로 도착하면 사라진다", async () => {
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033 })],
      "F5 1031": [problem(409, "STALE_COMPARISON")],
    });
    const env = makeStore();
    await mountChat(api, env.store);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);

    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착
    await flush();
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(comparisonToast()).toBeNull();
  });

  // 문구 키가 있는 F-5 오류 모두에 같은 규칙: 시간이 지나도 남고, 새 RECALC_RESULT가 오면 사라진다.
  async function expectKeptThenClearedByNewComparison(f5: unknown, copyKey: string, planReplies: Reply[] = [planView(13)]) {
    let arrived = false;
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        () => (arrived ? page([recalc(1033)], false) : page([], true)),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033 })],
      "F5 1031": [f5],
    });
    const env = makeStore({ planReplies });
    await mountChat(api, env.store);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy[copyKey]);
    await tick(60_000); // 그동안 폴링은 1033이 아직 PENDING이라고 답한다
    expect(comparisonToast()?.textContent).toContain(copy[copyKey]);

    arrived = true;
    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착
    await flush();
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(comparisonToast()).toBeNull();
  }

  it("'적용하지 못했어요'(500)도 60초 뒤 남아 있고 새 RECALC_RESULT가 오면 사라진다", async () => {
    await expectKeptThenClearedByNewComparison(serverError(), "toast.applyFailed");
  });

  it("'가장 최근 비교 결과만 적용할 수 있어요'(STALE_COMPARISON)는 60초 뒤에도 남아 있다가 새 RECALC_RESULT가 오면 사라진다", async () => {
    await expectKeptThenClearedByNewComparison(problem(409, "STALE_COMPARISON"), "toast.applyStaleComparison");
  });

  it("'지금 기준으로는 적용할 수 없는 값이에요'(VALIDATION_FAILED)는 60초 뒤에도 남아 있다가 새 RECALC_RESULT가 오면 사라진다", async () => {
    await expectKeptThenClearedByNewComparison(problem(422, "VALIDATION_FAILED"), "toast.applyNotApplicable");
  });

  it("'지금 기준으로는 적용할 수 없는 값이에요'(FIELD_NOT_CHANGEABLE)는 60초 뒤에도 남아 있다가 새 RECALC_RESULT가 오면 사라진다", async () => {
    await expectKeptThenClearedByNewComparison(problem(422, "FIELD_NOT_CHANGEABLE"), "toast.applyNotApplicable");
  });

  it("'계획이 다른 곳에서 바뀌었어요'(PLAN_CHANGED)는 60초 뒤에도 남아 있다가 새 RECALC_RESULT가 오면 사라진다", async () => {
    await expectKeptThenClearedByNewComparison(problem(409, "PLAN_CHANGED"), "toast.applyPlanChanged", [planView(14)]);
  });

  // 사라지는 때는 새 RECALC_RESULT가 '도착한' 때다. 그 메시지의 F-4 응답(성공·실패)을 기다리지 않는다.
  async function expectClearedOnArrival(f4New: Reply) {
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F4 1033": [f4New],
      "F5 1031": [problem(409, "STALE_COMPARISON")],
    });
    const env = makeStore();
    await mountChat(api, env.store);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);

    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착
    expect(bubble(1033)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(comparisonToast()).toBeNull();
    await flush();
    await flush();
    expect(comparisonToast()).toBeNull();
    return api;
  }

  it("새 RECALC_RESULT의 F-4가 아직 응답 전이어도 메시지가 도착한 즉시 오류 안내가 사라진다", async () => {
    const pending = deferred<unknown>();
    const api = await expectClearedOnArrival(pending.promise);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
  });

  it("새 RECALC_RESULT의 F-4가 500으로 실패해도 오류 안내는 사라진 채로 남는다", async () => {
    await expectClearedOnArrival(serverError());
    expect(document.querySelector('[data-testid="chat-comparison-failed"][data-message-id="1033"]')?.textContent).toContain(
      copy["comparison.loadFailed"],
    );
  });

  // 재시도 가능 오류는 다시 누르면(다음 적용 시도) 그 요청이 끝나기 전에 안내가 사라진다.
  async function expectClearedByRetry(error: unknown) {
    const retry = deferred<unknown>();
    await mountApply([error, retry.promise]);
    await clickApply();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    fireEvent.click(buttonOf(1031));
    await flush();
    expect(comparisonToast()).toBeNull();
    retry.resolve({ revision: 13 });
    await flush();
    await flush();
  }

  it("네트워크 실패의 '적용하지 못했어요'도 다시 누르면 응답 전에 사라진다", async () => {
    await expectClearedByRetry(networkError());
  });

  it("400 MALFORMED_REQUEST의 '적용하지 못했어요'도 다시 누르면 응답 전에 사라진다", async () => {
    await expectClearedByRetry(malformed());
  });
});

/**
 * 1031 적용 요청 중에 1033이 도착·활성이 되고 사용자가 1033도 적용한다. 그 뒤 1031의 오류가 늦게 온다(P-52).
 * 1033 적용을 시작한 뒤에는 1031의 늦은 오류가 1033의 안내·되돌리기 토스트를 덮지 않는다.
 */
async function mountTwoApplies(f5New: Reply[], { planReplies }: { planReplies?: Reply[] } = {}) {
  const older = deferred<unknown>();
  const api = makeApi({
    F2: [
      page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
      page([recalc(1033)], false),
    ],
    "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
    "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033, applicable: true })],
    "F5 1031": [older.promise],
    "F5 1033": f5New,
  });
  const env = makeStore(planReplies ? { planReplies } : {});
  await mountChat(api, env.store);
  fireEvent.click(buttonOf(1031));
  await flush();
  await tick(1000); // 폴링: 1033 RECALC_RESULT 도착, F-4 applicable=true
  await flush();
  await flush();
  expect(buttonOf(1033).disabled).toBe(false);
  return { api, older, ...env };
}

const appliedPaths = (api: ReturnType<typeof makeApi>) => api.f5Calls().map((c) => c.path);

describe("AC-29 더 새 비교의 적용을 시작한 뒤 도착한 이전 적용의 오류는 안내를 덮지 않는다 (C12b-7-2, 잠정 결정 P-52)", () => {
  it("1033 적용이 성공(되돌리기 토스트)한 뒤 1031의 STALE_COMPARISON이 늦게 와도 오류 안내가 뜨지 않고 되돌리기 토스트가 남는다", async () => {
    const { api, older, store, planGets } = await mountTwoApplies([{ revision: 13 }]);
    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    await flush();
    expect(store.getState().revision).toBe(13);
    expect(undoButton()).not.toBeNull();

    older.reject(problem(409, "STALE_COMPARISON"));
    await flush();
    await flush();
    expectNoApplyErrorNotice();
    expect(undoButton(), "늦게 온 1031의 오류가 1033의 되돌리기 토스트를 닫았다").not.toBeNull();
    expect(store.getState().revision).toBe(13);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(appliedPaths(api)).toEqual(["/api/chat/messages/1031/apply", "/api/chat/messages/1033/apply"]);
    expect(planGets).toHaveLength(1);
  });

  it("1033 적용이 응답 전일 때 1031의 500이 늦게 와도 오류 안내가 뜨지 않고, 그 뒤 1033 성공의 되돌리기 토스트가 보인다", async () => {
    const newer = deferred<unknown>();
    const { api, older, store } = await mountTwoApplies([newer.promise]);
    fireEvent.click(buttonOf(1033));
    await flush();
    expect(buttonOf(1033).disabled).toBe(true); // 요청 중

    older.reject(serverError());
    await flush();
    await flush();
    expectNoApplyErrorNotice();

    newer.resolve({ revision: 13 });
    await flush();
    await flush();
    await flush();
    expectNoApplyErrorNotice();
    expect(undoButton()).not.toBeNull();
    expect(store.getState().revision).toBe(13);
    expect(appliedPaths(api)).toEqual(["/api/chat/messages/1031/apply", "/api/chat/messages/1033/apply"]);
  });

  it("1033 적용이 422 VALIDATION_FAILED로 안내를 띄운 뒤 1031의 STALE_COMPARISON이 늦게 와도 1033의 안내가 그대로 남는다", async () => {
    const { older, store } = await mountTwoApplies([problem(422, "VALIDATION_FAILED", [{ field: "monthlySavingAmount", reason: "OUT_OF_RANGE" }])]);
    fireEvent.click(buttonOf(1033));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyNotApplicable"]);

    older.reject(problem(409, "STALE_COMPARISON"));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyNotApplicable"]);
    expect(comparisonToast()?.textContent ?? "").not.toContain(copy["toast.applyStaleComparison"]);
    expect(store.getState().revision).toBe(12);
    expect(undoButton()).toBeNull();
  });

  it("1033을 아직 누르지 않았다면 1031의 늦은 STALE_COMPARISON 안내는 그대로 보인다(AC-5와 같다)", async () => {
    const { older } = await mountTwoApplies([{ revision: 13 }]);
    older.reject(problem(409, "STALE_COMPARISON"));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyStaleComparison"]);
    expect(buttonOf(1033).disabled).toBe(false);
  });

  it("1033 적용이 응답 전일 때 1031의 PLAN_CHANGED가 늦게 와도 안내는 띄우지 않지만, 계획 재조회(loadPlan)는 그대로 하고 새 revision(14)을 받으면 모든 버튼이 비활성이다", async () => {
    const newer = deferred<unknown>();
    const { api, older, store, planGets } = await mountTwoApplies([newer.promise], { planReplies: [planView(14)] });
    const loadPlan = vi.spyOn(store, "loadPlan");
    fireEvent.click(buttonOf(1033));
    await flush();
    expect(buttonOf(1033).disabled).toBe(true); // 요청 중

    older.reject(problem(409, "PLAN_CHANGED"));
    await flush();
    await flush();
    await flush();
    expectNoApplyErrorNotice();
    expect(loadPlan, "늦은 PLAN_CHANGED라고 계획 재조회까지 건너뛰었다").toHaveBeenCalledTimes(1);
    expect((loadPlan.mock.calls[0][0] as { fresh?: boolean } | undefined)?.fresh).not.toBe(true);
    expect(planGets).toEqual([{ revisionAtSend: 12 }]);
    expect(store.getState().revision).toBe(14);
    expect(applyButtons().length).toBeGreaterThanOrEqual(2);
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(undoButton()).toBeNull();
    expect(appliedPaths(api)).toEqual(["/api/chat/messages/1031/apply", "/api/chat/messages/1033/apply"]);
  });
});
