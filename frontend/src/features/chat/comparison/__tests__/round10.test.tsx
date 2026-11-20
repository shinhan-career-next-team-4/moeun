// @vitest-environment jsdom
// Issue #53 P-65·P-66(라운드 15): 시나리오 코드는 GOOD·AVG·BAD만 받고(AC-13), F-5 성공은 안전한 양의 정수 revision만 받는다(AC-17).
// 진짜 래퍼(makeLiveEnv) + 응답 본문 그대로. 기존 잠긴 테스트는 고치지 않고 이 파일에 더한다.
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { jsonResponse } from "../../../../api/__tests__/helpers";
import { copy, doneMsg, flush, page, tick, userMsg } from "../../__tests__/helpers";
import { applyButtons, buttonOf, card, comparison, comparisonToast, failedNotice, makeLiveEnv, mountChat, planView } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const APPLY_PATH = "/api/chat/messages/1031/apply";
const undoButton = () => screen.queryByRole("button", { name: "되돌리기" });

describe("AC-13 시나리오 코드는 GOOD·AVG·BAD만 받는다 (P-65)", () => {
  // 다른 필드는 모두 정상이고 시나리오 코드만 미등록(UNKNOWN). 표시값도 그 코드 키로 채워 파싱 단계의 코드 검사만 걸리게 한다.
  function withUnknownScenario(where: "byScenario" | "current" | "changed") {
    const body = comparison("get-comparison.200.json", { messageId: 1031, applicable: true }) as any;
    const display = body.display as Record<string, string>;
    for (const key of Object.keys(display)) {
      if (key.includes(".AVG.")) display[key.replace(".AVG.", ".UNKNOWN.")] = display[key];
    }
    if (where === "byScenario") {
      const avg = body.diff.byScenario.find((item: { scenario: string }) => item.scenario === "AVG");
      avg.scenario = "UNKNOWN";
    } else {
      const avg = body[where].scenarios.find((item: { scenario: string }) => item.scenario === "AVG");
      avg.scenario = "UNKNOWN";
    }
    return body;
  }

  async function expectLoadFailedLikeServerError(broken: unknown) {
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") {
        return jsonResponse(200, page([userMsg(1030), recalc(1031)], false));
      }
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") return jsonResponse(200, broken);
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    const f4Fetches = () => env.fetchStub.mock.calls.filter(([input]) => String(input).endsWith("/comparison")).length;
    await mountChat(env.api, env.store);
    await tick(0);
    await flush();
    expect(f4Fetches()).toBe(1);
    expect(failedNotice(1031)?.textContent, "미등록 시나리오 코드 F-4 200에 '비교 결과를 불러오지 못했어요'가 없다").toContain(copy["comparison.loadFailed"]);
    expect(card(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(0);
    await tick(30_000); // 자동 재시도 없음
    await flush();
    expect(f4Fetches()).toBe(1);
    expect(applyButtons()).toHaveLength(0);
    expect(env.navigations).toEqual([]);
    expect(screen.getByTestId("chat-window")).toBeTruthy();
  }

  it("diff.byScenario에 미등록 코드(UNKNOWN)가 있으면 500과 같이 comparison.loadFailed, 카드·버튼 없음, 자동 재시도 없음", async () => {
    await expectLoadFailedLikeServerError(withUnknownScenario("byScenario"));
  });

  it("current.scenarios에 미등록 코드(UNKNOWN)가 있으면 500과 같이 comparison.loadFailed, 카드·버튼 없음, 자동 재시도 없음", async () => {
    await expectLoadFailedLikeServerError(withUnknownScenario("current"));
  });

  it("changed.scenarios에 미등록 코드(UNKNOWN)가 있으면 500과 같이 comparison.loadFailed, 카드·버튼 없음, 자동 재시도 없음", async () => {
    await expectLoadFailedLikeServerError(withUnknownScenario("changed"));
  });
});

describe("AC-17 F-5 성공은 안전한 양의 정수 revision만 받는다 (P-66)", () => {
  // 안전 정수 범위 밖·0·음수는 JSON 숫자 그대로 보낸다(JSON.stringify는 1e20을 100000000000000000000으로 쓴다).
  const unsafeBodies: Array<[string, string]> = [
    ["200 본문 안전 정수 범위 밖 100000000000000000000", '{"revision":100000000000000000000}'],
    ["200 본문 안전 정수 바로 위 9007199254740993", '{"revision":9007199254740993}'],
    ["200 본문 0", '{"revision":0}'],
    ["200 본문 음수 -1", '{"revision":-1}'],
  ];

  it.each(unsafeBodies)("%s: '적용하지 못했어요', 버튼 활성 그대로, revision 12, 되돌리기 토스트·재조회 없음, 다시 누르면 성공한다", async (name, raw) => {
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
        if (applyCount === 1) return new Response(raw, { status: 200, headers: { "Content-Type": "application/json" } });
        return jsonResponse(200, { revision: 13 });
      }
      if (method === "GET" && requestPath === "/api/plan") return jsonResponse(200, planView(13));
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    const planFetches = () => env.fetchStub.mock.calls.filter(([input]) => String(input) === "/api/plan").length;
    await mountChat(env.api, env.store);
    await tick(0);
    expect(buttonOf(1031).disabled).toBe(false);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();
    await tick(0);
    expect(comparisonToast()?.textContent, `${name}: '적용하지 못했어요'가 없다`).toContain(copy["toast.applyFailed"]);
    expect(buttonOf(1031).disabled, `${name}: 버튼이 활성 그대로가 아니다`).toBe(false);
    expect(env.store.getState().revision, `${name}: store revision이 바뀌었다`).toBe(12);
    expect(undoButton(), `${name}: 되돌리기 토스트가 떴다`).toBeNull();
    expect(planFetches(), `${name}: 결과 재조회를 했다`).toBe(0);
    expect(env.navigations).toEqual([]);

    fireEvent.click(buttonOf(1031)); // 다시 시도
    await flush();
    await flush();
    await flush();
    await tick(0);
    expect(applyCount).toBe(2);
    expect(env.store.getState().revision).toBe(13);
    expect(undoButton()).not.toBeNull();
  });
});
