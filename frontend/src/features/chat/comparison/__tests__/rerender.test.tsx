// @vitest-environment jsdom
// Issue #53 AC-31: 같은 인스턴스를 담은 새 deps 객체로 rerender해도 비교 상태가 초기화되지 않는다.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPollingRegistry } from "../../../../api/polling";
import { copy, doneMsg, flush, page, serverError, tick, userMsg } from "../../__tests__/helpers";
import { ChatWindow } from "../../index";
import {
  applyButtons,
  buttonOf,
  comparison,
  comparisonToast,
  failedNotice,
  makeApi,
  makeStore,
  planView,
  type Reply,
} from "./helpers";

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");
const f4Path = (id: number) => `/api/chat/messages/${id}/comparison`;
const undoButton = () => screen.queryByRole("button", { name: "되돌리기" });

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/** 복원 픽스처(1029·1031)를 마운트한다. rerender는 같은 api·polling·store를 담은 새 deps 객체로 한다. */
async function mount({
  f4 = [comparison("get-comparison.200.json", { messageId: 1031 })] as Reply[],
  f5 = [{ revision: 13 }] as Reply[],
} = {}) {
  const api = makeApi({
    F2: [page([userMsg(1028), recalc(1029), userMsg(1030), recalc(1031)], false)],
    "F4 1031": f4,
    "F5 1031": f5,
  });
  const env = makeStore({ initialRevision: 12, planReplies: [planView(13)] });
  const polling = createPollingRegistry();
  const view = render(<ChatWindow deps={{ api, polling, planStore: env.store } as any} />);
  await flush();
  await flush();
  const rerender = async () => {
    view.rerender(<ChatWindow deps={{ api, polling, planStore: env.store } as any} />);
    await flush();
    await flush();
  };
  return { api, rerender, ...env };
}

describe("AC-31 같은 인스턴스를 담은 새 deps로 다시 렌더링해도 비교 상태가 유지된다 (P-67)", () => {
  it("기본: F-4 완료 뒤 rerender해도 1031 F-4는 1회이고 1031 버튼은 활성 그대로다", async () => {
    const { api, rerender } = await mount();
    expect(buttonOf(1031).disabled).toBe(false);
    await rerender();
    await tick(10_000);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
    expect(buttonOf(1031).disabled).toBe(false);
  });

  it("① rerender 전 store.acceptRevision(13)이면 rerender 뒤에도 1031은 비활성이다", async () => {
    const { api, store, rerender } = await mount();
    act(() => store.acceptRevision(13));
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    await rerender();
    for (const button of applyButtons()) expect(button.disabled).toBe(true);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });

  it("② 최초 F-4 500이면 rerender 뒤에도 comparison.loadFailed 안내가 남고 F-4를 다시 부르지 않는다", async () => {
    const { api, rerender } = await mount({ f4: [serverError()] });
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    await rerender();
    await tick(10_000);
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    expect(applyButtons().filter((b) => !b.disabled)).toHaveLength(0);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });

  it("③ 적용 F-5 500이면 rerender 뒤에도 toast.applyFailed 안내가 남는다", async () => {
    const { api, rerender } = await mount({ f5: [serverError()] });
    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    await rerender();
    expect(comparisonToast()?.textContent).toContain(copy["toast.applyFailed"]);
    expect(buttonOf(1031).disabled).toBe(false);
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });

  it("④ F-5 성공과 재조회 완료 뒤 rerender해도 revision 13이고 되돌리기 토스트는 적용 성공부터 10초 보인다", async () => {
    const { api, store, rerender } = await mount();
    fireEvent.click(buttonOf(1031));
    await flush();
    await flush();
    await flush();
    expect(store.getState().revision).toBe(13);
    expect(undoButton()).not.toBeNull();
    await tick(4_000);
    await rerender();
    expect(store.getState().revision).toBe(13);
    expect(undoButton()).not.toBeNull();
    await tick(5_900);
    expect(undoButton()).not.toBeNull();
    await tick(200);
    expect(undoButton()).toBeNull();
    expect(api.f4Calls()).toEqual([f4Path(1031)]);
  });
});
