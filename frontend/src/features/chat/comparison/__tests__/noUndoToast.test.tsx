// @vitest-environment jsdom
// #394: 대화창에서 비교를 적용해도 "조건을 적용했어요 / 되돌리기" 토스트는 뜨지 않는다. 되돌리기는 조건 바 버튼이 반짝여 알린다(#361).
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { doneMsg, flush, page, userMsg } from "../../__tests__/helpers";
import { buttonOf, comparison, makeApi, makeStore, mountChat, planView } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("적용 뒤 토스트 없음", () => {
  it("적용이 성공하면 계획 revision은 바뀌고(F-5), 되돌리기 토스트와 안내 문구는 그려지지 않는다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), doneMsg(1031, "RECALC_RESULT", "월 저축액을 50만원으로 바꿔 보면 달성해요")], false)],
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031, applicable: true })],
      "F5 1031": [{ revision: 13 }],
    });
    const { store } = makeStore({ initialRevision: 12, planReplies: [planView(13)] });
    await mountChat(api, store);

    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();

    expect(api.f5Calls()).toHaveLength(1);
    expect(store.getState().revision).toBe(13);
    expect(screen.queryByRole("button", { name: "되돌리기" })).toBeNull();
    expect(screen.queryByText("조건을 적용했어요.")).toBeNull();
  });
});
