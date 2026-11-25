// @vitest-environment jsdom
// Issue #53 P-71(라운드 23): diff.byScenario와 current·changed 시나리오 목록의 교집합 일치는 거절 조건이 아니다.
// diff만 AVG 하나로 줄어도 표시값·코드 검증을 통과하면 카드가 뜨고 applicable=true가 반영된다.
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { jsonResponse } from "../../../../api/__tests__/helpers";
import { doneMsg, flush, page, tick, userMsg } from "../../__tests__/helpers";
import { applyButtons, buttonOf, card, comparison, failedNotice, makeLiveEnv, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("P-71 diff.byScenario가 양쪽 시나리오 목록보다 적어도 거절하지 않는다", () => {
  async function mountWithAvgOnlyDiff(applicable: boolean) {
    const body = comparison("get-comparison.200.json", { messageId: 1031, applicable }) as any;
    body.diff.byScenario = body.diff.byScenario.filter((item: { scenario: string }) => item.scenario === "AVG");
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") {
        return jsonResponse(200, page([userMsg(1030), doneMsg(1031, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요")], false));
      }
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") return jsonResponse(200, body);
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    await mountChat(env.api, env.store);
    await tick(0);
    await flush();
  }

  it("diff.byScenario만 AVG 1개로 줄인 200 + applicable=true: 실패 안내 없이 AVG 카드와 활성 적용 버튼을 그린다", async () => {
    await mountWithAvgOnlyDiff(true);
    expect(failedNotice(1031)).toBeNull();
    const shown = card(1031);
    expect(shown).not.toBeNull();
    expect(shown?.querySelectorAll("[data-scenario]")).toHaveLength(1);
    expect(shown?.querySelector('[data-scenario="AVG"]')).not.toBeNull();
    expect(buttonOf(1031).disabled).toBe(false);
  });

  it("같은 입력에서 applicable=false이면 카드는 뜨고 적용 버튼은 비활성이다(파싱한 applicable이 버튼을 정한다)", async () => {
    await mountWithAvgOnlyDiff(false);
    expect(failedNotice(1031)).toBeNull();
    expect(card(1031)).not.toBeNull();
    expect(applyButtons().every((button) => button.disabled)).toBe(true);
  });
});
