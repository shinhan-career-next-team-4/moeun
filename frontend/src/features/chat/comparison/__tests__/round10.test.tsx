// @vitest-environment jsdom
// Issue #53 P-65·P-66(라운드 15): 시나리오 코드는 GOOD·AVG·BAD만 받고(AC-13), F-5 성공은 안전한 양의 정수 revision만 받는다(AC-17).
// 진짜 래퍼(makeLiveEnv) + 응답 본문 그대로. 기존 잠긴 테스트는 고치지 않고 이 파일에 더한다.
import { cleanup, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { jsonResponse } from "../../../../api/__tests__/helpers";
import { copy, doneMsg, flush, page, tick, userMsg } from "../../__tests__/helpers";
import { applyButtons, card, comparison, failedNotice, makeLiveEnv, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");

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
