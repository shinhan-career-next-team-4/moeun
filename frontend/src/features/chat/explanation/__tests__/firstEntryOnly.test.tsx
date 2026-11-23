// @vitest-environment jsdom
// Issue #280: 결과 자동 설명(F-3)은 처음 진입 때 한 번뿐이다. 왼쪽 화면에서 조건을 바꿔 calcRunId가 바뀌어도 다시 보내지 않고,
// 새로고침 뒤에는 탭 메모리가 아니라 서버 대화 기록(F-2)에 결과 설명이 있는지로 처음 진입인지 정한다.
import { act, cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred } from "../../../../api/__tests__/helpers";
import { flush, serverError, tick } from "../../__tests__/helpers";
import { chatPage, explanationDone, explanationFailed, freshId, makeStore, mountChat, view } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function calculatedStore(revision: number, calcRunId: number) {
  const made = makeStore();
  made.store.acceptPlanView(view(revision, "CALCULATED", calcRunId));
  return made;
}

describe("처음 진입", () => {
  it("대화 기록에 결과 설명이 없으면 한 번 보내고, 왼쪽 변경으로 calcRunId가 바뀌어도 더 보내지 않는다", async () => {
    const [x, y] = [freshId(), freshId()];
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);

    act(() => store.acceptPlanView(view(13, "CALCULATED", y))); // 입력 수정 저장·비교 적용·ETF 카드 적용
    await tick(5000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });

  it("대화 기록을 받기 전에는 보내지 않는다(새로고침 직후 기록에 설명이 있는지 아직 모른다)", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const restore = deferred<unknown>();
    const { f3Bodies } = await mountChat(store, { get: [restore.promise] });
    expect(f3Bodies()).toEqual([]);

    await act(async () => restore.resolve(chatPage([], false)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });
});

describe("새로고침", () => {
  it("대화 기록에 결과 설명이 이미 있으면(새 탭 메모리여도) 다시 보내지 않는다", async () => {
    const { store } = calculatedStore(12, freshId());
    const { f3Bodies } = await mountChat(store, { get: [chatPage([explanationDone(901, "앞선 설명")], false)] });
    await tick(5000);
    expect(f3Bodies()).toEqual([]);
  });

  it("기록에 있는 것이 실패한 설명뿐이면 처음 진입으로 보고 보낸다", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store, { get: [chatPage([explanationFailed(902, "실패")], false)] });
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });
});

describe("(예외) 대화 기록을 받지 못함", () => {
  it("복원이 계속 실패해 폴링이 멈추면 기다리지 않고 한 번 보낸다", async () => {
    const x = freshId();
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store, { get: [serverError()] });
    await tick(30000);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);
  });
});

describe("로그아웃·재로그인", () => {
  it("store가 비워진 뒤(대화 기록도 지워짐) 다시 들어온 결과에는 한 번 보낸다", async () => {
    const [x, y] = [freshId(), freshId()];
    const { store } = calculatedStore(12, x);
    const { f3Bodies } = await mountChat(store);
    expect(f3Bodies()).toEqual([{ calcRunId: x }]);

    act(() => store.clear());
    act(() => store.acceptPlanView(view(1, "CALCULATED", y)));
    await flush();
    expect(f3Bodies()).toEqual([{ calcRunId: x }, { calcRunId: y }]);
  });
});
