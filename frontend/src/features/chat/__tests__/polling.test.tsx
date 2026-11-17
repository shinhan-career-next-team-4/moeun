// @vitest-environment jsdom
// Issue #52 AC-5~AC-9: 폴링 훅 useChatPolling (F-2 폴링·복원, 복원 안전장치, 연속 실패, 401, 노출 값).
import { StrictMode, type ReactNode } from "react";
import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useChatPolling } from "../index";
import {
  doneMsg,
  flush,
  makeDeps,
  makeFakeApi,
  malformed,
  networkError,
  page,
  pendingMsg,
  pollPath,
  serverError,
  tick,
  unauthenticated,
  userMsg,
} from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const RESTORE = "/api/chat/messages";

function setup(replies: Parameters<typeof makeFakeApi>[0], wrapper?: ({ children }: { children: ReactNode }) => ReactNode) {
  const api = makeFakeApi(replies);
  const { deps, polling } = makeDeps(api);
  const hook = renderHook(() => useChatPolling(deps), wrapper ? { wrapper } : undefined);
  return { api, polling, hook };
}

const ids = (hook: ReturnType<typeof setup>["hook"]) => hook.result.current.messages.map((m) => m.id);

describe("AC-5 복원 1회와 1초 간격 폴링", () => {
  it("진입하면 afterId 없이 F-2를 한 번 부르고, 이후 1초 간격으로 afterId를 붙여 부른다", async () => {
    const { api } = setup({
      get: [
        page([userMsg(1030), pendingMsg(1031)], true),
        page([], true),
        page([], true),
        page([doneMsg(1031, "ANSWER", "답이에요")], false),
      ],
    });
    await flush();
    expect(api.gets()).toHaveLength(1);
    expect(api.gets()[0]).toMatchObject({ method: "GET", path: RESTORE });

    await tick(999);
    expect(api.gets()).toHaveLength(1);
    await tick(1);
    expect(api.gets()).toHaveLength(2);
    // afterId = 가진 PENDING 중 가장 작은 id − 1
    expect(api.gets()[1].path).toBe(pollPath(1030));

    await tick(999);
    expect(api.gets()).toHaveLength(2);
    await tick(1);
    expect(api.gets()).toHaveLength(3);
    expect(api.gets()[2].path).toBe(pollPath(1030));
  });

  it("받은 메시지는 id로 덮어쓰고(중복 없음) id 오름차순이며, hasPending=false면 멈춘다", async () => {
    const { api, hook } = setup({
      get: [
        page([doneMsg(1032, "ANSWER", "다른 답"), userMsg(1030), pendingMsg(1031)], true),
        page([pendingMsg(1031)], true), // 같은 메시지를 다시 받는 것은 정상
        page([doneMsg(1031, "RECALC_RESULT", "계산 결과예요")], false),
      ],
    });
    await flush();
    expect(ids(hook)).toEqual([1030, 1031, 1032]); // 받은 순서가 달라도 id 오름차순
    expect(api.gets()[0].path).toBe(RESTORE);

    await tick(1000);
    expect(ids(hook)).toEqual([1030, 1031, 1032]);

    await tick(1000);
    expect(ids(hook)).toEqual([1030, 1031, 1032]);
    const replaced = hook.result.current.messages.find((m) => m.id === 1031)!;
    expect(replaced).toMatchObject({ status: "COMPLETED", messageType: "RECALC_RESULT", content: "계산 결과예요" });
    expect(hook.result.current.hasPending).toBe(false);

    await tick(10_000);
    expect(api.gets()).toHaveLength(3);
  });

  it("PENDING이 없으면 afterId는 가진 가장 큰 id이고, 멈춘 뒤 resume()으로 다시 시작한다", async () => {
    const { api, hook } = setup({
      get: [
        page([userMsg(1030), doneMsg(1031, "ANSWER", "답")], false),
        page([userMsg(1032), pendingMsg(1033)], true),
        page([doneMsg(1033, "ANSWER", "또 답")], false),
      ],
    });
    await flush();
    await tick(10_000);
    expect(api.gets()).toHaveLength(1); // hasPending=false라 멈춰 있다

    act(() => hook.result.current.resume());
    await tick(999);
    expect(api.gets()).toHaveLength(1);
    await tick(1);
    expect(api.gets()).toHaveLength(2);
    expect(api.gets()[1].path).toBe(pollPath(1031)); // PENDING 없음 -> 가진 가장 큰 id

    await tick(1000);
    expect(api.gets()[2].path).toBe(pollPath(1032)); // PENDING 1033 − 1
    await tick(10_000);
    expect(api.gets()).toHaveLength(3);
    expect(ids(hook)).toEqual([1030, 1031, 1032, 1033]);
  });

  it("개발 모드(StrictMode)의 이중 effect에서도 복원은 한 번만 나간다", async () => {
    const { api } = setup({ get: [page([], false)] }, ({ children }) => <StrictMode>{children}</StrictMode>);
    await flush();
    await tick(5000);
    expect(api.gets()).toHaveLength(1);
  });

  it("언마운트하면 폴링이 멈춘다", async () => {
    const { api, hook } = setup({ get: [page([userMsg(1030), pendingMsg(1031)], true)] });
    await flush();
    await tick(1000);
    const before = api.gets().length;
    hook.unmount();
    await tick(5000);
    expect(api.gets()).toHaveLength(before);
  });
});

describe("AC-6 복원: 진입 때 PENDING이 섞여 있으면", () => {
  it("폴링이 이어지고 hasPending·answerPending이 true다 (get-messages.200-restore-pending 모양)", async () => {
    const { api, hook } = setup({
      get: [page([userMsg(1030), pendingMsg(1031)], true, true), page([], true, true)],
    });
    await flush();
    expect(ids(hook)).toEqual([1030, 1031]);
    expect(hook.result.current.hasPending).toBe(true);
    expect(hook.result.current.answerPending).toBe(true);
    await tick(1000);
    expect(api.gets()).toHaveLength(2);
    expect(api.gets()[1].path).toBe(pollPath(1030));
  });
});

describe("AC-7 복원 안전장치", () => {
  it("hasPending=true인데 가진 PENDING이 없으면 afterId 없이 복원을 1회 부르고, 다시 부르지 않는다", async () => {
    const answered = doneMsg(1031, "ANSWER", "답");
    const { api } = setup({
      get: [
        page([userMsg(1030), pendingMsg(1031)], true), // 복원
        page([answered], true), // 폴링: 서버는 PENDING이 있다는데 가진 PENDING은 없다
        page([answered], true), // 안전장치 복원. 같은 상태여도 더 부르지 않는다
        page([], false), // 이어진 폴링
      ],
    });
    await flush();
    await tick(10_000);
    const paths = api.gets().map((c) => c.path);
    expect(paths).toEqual([RESTORE, pollPath(1030), RESTORE, pollPath(1031)]);
  });

  it("hasPending=false인데 가진 PENDING이 남아 있으면 복원을 1회 부르고, 다시 부르지 않는다", async () => {
    const { api, hook } = setup({
      get: [
        page([userMsg(1030), pendingMsg(1031)], true), // 복원
        page([], false), // 폴링: 서버는 끝났다는데 가진 PENDING이 남음
        page([pendingMsg(1031)], false), // 안전장치 복원. 같은 상태여도 더 부르지 않는다
      ],
    });
    await flush();
    await tick(10_000);
    const paths = api.gets().map((c) => c.path);
    expect(paths).toEqual([RESTORE, pollPath(1030), RESTORE]);
    expect(hook.result.current.hasPending).toBe(false);
  });
});

describe("AC-8 연속 실패 3회", () => {
  it("401 외 실패(500·네트워크·400)가 연속 3회면 폴링을 멈추고 pollingFailed가 true다", async () => {
    const { api, hook } = setup({
      get: [page([userMsg(1030), pendingMsg(1031)], true), serverError(), networkError(), malformed()],
    });
    await flush();
    await tick(2000);
    expect(api.gets()).toHaveLength(3);
    expect(hook.result.current.pollingFailed).toBe(false); // 아직 2회
    await tick(1000);
    expect(api.gets()).toHaveLength(4);
    expect(hook.result.current.pollingFailed).toBe(true);
    await tick(10_000);
    expect(api.gets()).toHaveLength(4);
  });

  it("복원 호출(afterId 없음)의 실패도 같은 규칙으로 센다: 1초마다 다시 부르다 3회째에 멈춘다", async () => {
    const { api, hook } = setup({ get: [serverError(), serverError(), serverError()] });
    await flush();
    await tick(10_000);
    expect(api.gets().map((c) => c.path)).toEqual([RESTORE, RESTORE, RESTORE]);
    expect(hook.result.current.pollingFailed).toBe(true);
  });

  it("그 사이 200이 한 번 오면 연속 횟수가 0으로 돌아간다", async () => {
    const pending = [userMsg(1030), pendingMsg(1031)];
    const { api, hook } = setup({
      get: [
        page(pending, true),
        serverError(),
        serverError(),
        page([pendingMsg(1031)], true),
        networkError(),
        networkError(),
        page([doneMsg(1031, "ANSWER", "답")], false),
      ],
    });
    await flush();
    await tick(10_000);
    expect(api.gets()).toHaveLength(7);
    expect(hook.result.current.pollingFailed).toBe(false);
    expect(hook.result.current.hasPending).toBe(false);
  });

  it("멈춘 뒤 resume()하면 안내가 사라지고 횟수를 새로 센다(실패 2번 뒤 200이면 계속 간다)", async () => {
    const { api, hook } = setup({
      get: [
        page([userMsg(1030), pendingMsg(1031)], true),
        serverError(),
        serverError(),
        serverError(),
        serverError(), // 재개 뒤 1회
        serverError(), // 재개 뒤 2회
        page([doneMsg(1031, "ANSWER", "답")], false),
      ],
    });
    await flush();
    await tick(3000);
    expect(hook.result.current.pollingFailed).toBe(true);
    expect(api.gets()).toHaveLength(4);

    act(() => hook.result.current.resume());
    expect(hook.result.current.pollingFailed).toBe(false);
    await tick(10_000);
    expect(api.gets()).toHaveLength(7);
    expect(hook.result.current.pollingFailed).toBe(false);
    expect(hook.result.current.hasPending).toBe(false);
  });
});

describe("AC-4 401", () => {
  it("폴링 중 401을 받으면 더 부르지 않고 실패 안내도 내지 않는다(이동은 래퍼가 한다)", async () => {
    const { api, hook } = setup({
      get: [page([userMsg(1030), pendingMsg(1031)], true), unauthenticated(), page([], true)],
    });
    await flush();
    await tick(10_000);
    expect(api.gets()).toHaveLength(2);
    expect(hook.result.current.pollingFailed).toBe(false);
  });

  it("래퍼가 폴링 레지스트리의 stopAll()을 부르면(401 처리) 폴링이 멈춘다", async () => {
    const { api, polling } = setup({ get: [page([userMsg(1030), pendingMsg(1031)], true)] });
    await flush();
    await tick(2000);
    const before = api.gets().length;
    expect(before).toBeGreaterThanOrEqual(2);
    polling.stopAll();
    await tick(10_000);
    expect(api.gets()).toHaveLength(before);
  });
});

describe("AC-9 훅이 노출하는 값", () => {
  it("hasPending·answerPending은 서버 응답 값 그대로다(메시지 내용·PENDING 유무로 추정하지 않는다)", async () => {
    const { hook } = setup({
      get: [
        page([userMsg(1030), pendingMsg(1031)], true, true),
        page([pendingMsg(1032)], true, false), // 설명 대기만 있다
        page([doneMsg(1032, "EXPLANATION", "설명")], false, false),
      ],
    });
    await flush();
    expect(hook.result.current).toMatchObject({ hasPending: true, answerPending: true });
    await tick(1000);
    expect(hook.result.current).toMatchObject({ hasPending: true, answerPending: false });
    await tick(1000);
    expect(hook.result.current).toMatchObject({ hasPending: false, answerPending: false });
  });

  it("messages·hasPending·answerPending·pollingFailed·resume을 돌려준다", async () => {
    const { hook } = setup({ get: [page([], false)] });
    await flush();
    const value = hook.result.current;
    expect(Array.isArray(value.messages)).toBe(true);
    expect(typeof value.hasPending).toBe("boolean");
    expect(typeof value.answerPending).toBe("boolean");
    expect(typeof value.pollingFailed).toBe("boolean");
    expect(typeof value.resume).toBe("function");
  });
});
