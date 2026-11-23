// @vitest-environment jsdom
// Issue #54 완료 조건 "목 모드에서 설명 성공·실패 확인": 진짜 래퍼(#13, mock 모드)·진짜 store·src/mocks/chat/*.json으로
// F-3 → 폴링 → 설명 도착(출처 링크)·FAILED 안내·F-3 404/500/401을 확인한다.
import { act, cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { resetMocks, useMock } from "../../../../api/mock";
import { LOGIN_PATH } from "../../../../app/routes";
import { ChatWindow } from "../../index";
import { copy, makeMockEnv } from "../../__tests__/helpers";
import { bubble, expandEtfRisk, F3_PATH, freshId, view } from "./helpers";

beforeAll(async () => {
  await import("../../../../api/mock"); // 첫 동적 import가 테스트 시간 안에 끝나도록 미리 읽는다
});

beforeEach(() => {
  resetMocks();
  // setImmediate는 진짜로 둔다: 목 응답(Response 파싱)이 끝날 때까지 settle()이 기다릴 수 있어야 한다.
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  resetMocks();
});

const settle = async () => {
  await act(async () => {
    for (let i = 0; i < 20; i += 1) await new Promise((resolve) => setImmediate(resolve));
  });
};

const advance = async (ms: number) => {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
  await settle();
};

/** 기본 목(복원 빈 목록)으로 대화창을 연다. 계획 결과는 아직 없다. */
async function mountMock() {
  const env = makeMockEnv("/result");
  const loadPlan = vi.spyOn(env.store, "loadPlan");
  render(<ChatWindow deps={env.deps} />);
  await advance(0);
  return { env, loadPlan };
}

const f3Requests = (env: ReturnType<typeof makeMockEnv>) =>
  env.requests.filter((r) => r.method === "POST" && r.path === F3_PATH);

describe("목 모드: 설명 성공", () => {
  it("결과가 store에 들어오면 F-3(post-explanations.202)을 보내고, get-messages.seq-explanation으로 설명 1032의 대기 표시 → 같은 id의 설명과 두 운용사 출처 링크가 보인다", async () => {
    const { env } = await mountMock();
    useMock("get-messages.seq-explanation.json");
    const x = freshId();
    act(() => env.store.acceptPlanView(view(12, "CALCULATED", x)));
    await settle();
    expect(f3Requests(env)).toEqual([{ method: "POST", path: F3_PATH, body: { calcRunId: x } }]);

    // F-3 뒤 첫 F-2(seq 첫 응답: 1032 PENDING)가 반영된 직후를 본다. 다음 주기(1초)는 아직 오지 않았다.
    const f3Index = env.requests.findIndex((r) => r.method === "POST" && r.path === F3_PATH);
    const pollsAfterF3 = () =>
      env.requests.slice(f3Index + 1).filter((r) => r.method === "GET" && r.path.startsWith("/api/chat/messages")).length;
    for (let i = 0; i < 30 && pollsAfterF3() === 0; i += 1) await advance(100);
    expect(pollsAfterF3()).toBe(1);
    const pendingIds = () => screen.queryAllByTestId("chat-pending").map((el) => el.getAttribute("data-message-id"));
    expect(pendingIds()).toEqual(["1032"]);
    expect(bubble(1032)).toBeUndefined();

    for (let i = 0; i < 30 && !bubble(1032); i += 1) await advance(100);
    expect(pollsAfterF3()).toBeGreaterThanOrEqual(2);
    expect(pendingIds()).toEqual([]);
    const message = bubble(1032);
    expect(message?.getAttribute("data-message-type")).toBe("EXPLANATION");
    expect(message?.textContent).toContain("S&P500 추종 ETF");
    expandEtfRisk(message!);
    const links = within(message!).getAllByRole("link");
    expect(links.map((a) => a.textContent)).toEqual(["KODEX 미국S&P500 투자설명서", "TIGER 미국S&P500 투자설명서"]);
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      "https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf",
      "https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf",
    ]);
    expect(message?.textContent).toContain("삼성자산운용");
    expect(message?.textContent).toContain("미래에셋자산운용");
    expect(screen.queryAllByTestId("chat-pending")).toHaveLength(0);
  });
});

describe("목 모드: 설명 실패", () => {
  it("get-messages.200-explanation-failed: F-3 뒤 FAILED 설명이 오면 문구표 실패 안내가 보이고 공유 store는 그대로다", async () => {
    const { env, loadPlan } = await mountMock();
    useMock("get-messages.200-explanation-failed.json");
    const x = freshId();
    act(() => env.store.acceptPlanView(view(12, "CALCULATED", x)));
    const before = env.store.getState();
    await settle();
    expect(f3Requests(env)).toHaveLength(1);

    await advance(1000);
    await advance(1000);
    const failed = bubble(1032);
    expect(failed?.getAttribute("data-status")).toBe("FAILED");
    expect(failed?.textContent).toContain(copy["chat.failed"]);
    expect(env.store.getState()).toBe(before);
    expect(loadPlan).not.toHaveBeenCalled();
  });

  it("post-explanations.500: 대화창에 아무것도 그리지 않는다", async () => {
    const { env, loadPlan } = await mountMock();
    useMock("post-explanations.500.json");
    act(() => env.store.acceptPlanView(view(12, "CALCULATED", freshId())));
    const markup = screen.getByTestId("chat-window").innerHTML; // 결과가 그려진 직후(F-3 응답 전)의 대화창
    await settle();
    expect(f3Requests(env)).toHaveLength(1);
    await advance(5000);
    expect(screen.getByTestId("chat-window").innerHTML).toBe(markup);
    expect(loadPlan).not.toHaveBeenCalled();
  });

  it("post-explanations.404: loadPlan()으로 다시 불러와(get-plan.200, calcRunId 540) 바뀐 calcRunId로 한 번만 다시 보내고, 또 404면 멈추며 아무것도 그리지 않는다", async () => {
    const { env, loadPlan } = await mountMock();
    useMock("post-explanations.404.json");
    useMock("get-plan.200.json");
    const x = freshId();
    act(() => env.store.acceptPlanView(view(12, "CALCULATED", x)));
    const markup = screen.getByTestId("chat-window").innerHTML; // 결과가 그려진 직후(F-3 응답 전)의 대화창
    await settle();
    await advance(5000);
    expect(loadPlan).toHaveBeenCalledTimes(1);
    expect(env.store.getState().view?.calcRunId).toBe(540);
    expect(f3Requests(env).map((r) => r.body)).toEqual([{ calcRunId: x }, { calcRunId: 540 }]);
    expect(screen.getByTestId("chat-window").innerHTML).toBe(markup);
  });

  it("401(common.401-unauthenticated): 래퍼가 store를 비우고 로그인 화면으로 보낸다. 대화창에 오류 문구는 없다", async () => {
    const { env } = await mountMock();
    useMock("common.401-unauthenticated.json");
    act(() => env.store.acceptPlanView(view(12, "CALCULATED", freshId())));
    await settle();
    expect(f3Requests(env)).toHaveLength(1);
    expect(env.navigations).toEqual([LOGIN_PATH]);
    expect(env.store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
    expect(screen.queryByTestId("chat-toast")).toBeNull();
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
  });
});
