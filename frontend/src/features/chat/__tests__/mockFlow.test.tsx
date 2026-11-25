// @vitest-environment jsdom
// Issue #52 완료 조건: 목 모드(래퍼 #13 + src/mocks/chat/*.json)에서 PENDING -> COMPLETED/FAILED 전환, 복원, 401을 확인한다.
// 진짜 래퍼·진짜 store·진짜 폴링 레지스트리를 쓰고, 네트워크 대신 목 JSON을 쓴다.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { planView } from "../../../api/__tests__/helpers";
import { resetMocks, useMock } from "../../../api/mock";
import { LOGIN_PATH } from "../../../app/routes";
import { ChatWindow } from "../index";
import { copy, isF2Path, makeMockEnv } from "./helpers";

beforeAll(async () => {
  await import("../../../api/mock"); // 첫 동적 import가 테스트 시간 안에 끝나도록 미리 읽는다
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

async function mountMock(pathname = "/result") {
  const env = makeMockEnv(pathname);
  render(<ChatWindow deps={env.deps} />);
  await advance(0); // 구현이 복원을 타이머(0ms)로 시작해도 나가게 한다
  return env;
}

const bubble = (id: number) =>
  screen.queryAllByTestId("chat-message").find((el) => el.getAttribute("data-message-id") === String(id));
const pendings = () => screen.queryAllByTestId("chat-pending");
// F-2(복원·폴링)만 센다. #53 F-4(`/{id}/comparison`) GET은 넣지 않는다(#53 사용자 결정 4차 B1=A).
const chatGets = (env: ReturnType<typeof makeMockEnv>) =>
  env.requests.filter((r) => r.method === "GET" && isF2Path(r.path));

describe("목 모드: 복원", () => {
  it("기본 목(get-messages.200-restore-empty)이면 빈 목록이고 폴링하지 않는다. 고지는 보인다", async () => {
    const env = await mountMock();
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(0);
    expect(pendings()).toHaveLength(0);
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
    await advance(5000);
    expect(chatGets(env)).toHaveLength(1);
    expect(chatGets(env)[0].path).toBe("/api/chat/messages");
  });

  it("get-messages.200-restore-history: 7개 메시지를 id 오름차순으로 그린다", async () => {
    useMock("get-messages.200-restore-history.json");
    await mountMock();
    const ids = screen.getAllByTestId("chat-message").map((el) => el.getAttribute("data-message-id"));
    expect(ids).toEqual(["1025", "1026", "1027", "1028", "1029", "1030", "1031"]);
    expect(pendings()).toHaveLength(0);
  });

  it("get-messages.200-restore-pending: 대기 표시가 있고 보내기가 막혀 있으며 폴링이 이어진다", async () => {
    useMock("get-messages.200-restore-pending.json");
    const env = await mountMock();
    expect(bubble(1030)?.textContent).toContain("월 50만원으로 하면 어때요?");
    expect(pendings()).toHaveLength(1);
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(true);
    await advance(1000);
    expect(chatGets(env).map((r) => r.path)).toEqual(["/api/chat/messages", "/api/chat/messages?afterId=1030"]);
  });
});

describe("목 모드: PENDING -> COMPLETED / FAILED 전환", () => {
  it("get-messages.seq-question-recalc: 대기 -> RECALC_RESULT 말풍선, 그 뒤 보내기가 풀리고 폴링이 멈춘다", async () => {
    useMock("get-messages.seq-question-recalc.json");
    const env = await mountMock();
    expect(pendings()).toHaveLength(1);
    expect(bubble(1031)).toBeUndefined();

    await advance(1000);
    expect(pendings()).toHaveLength(0);
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(bubble(1031)?.textContent).toContain("월 저축액을 약 50만원으로 바꾸면");
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(2); // 같은 id는 덮어쓴다

    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "또 질문" } });
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(false);
    await advance(10_000);
    expect(chatGets(env).map((r) => r.path)).toEqual(["/api/chat/messages", "/api/chat/messages?afterId=1030"]);
  });

  it("PENDING이 FAILED로 바뀌면 문구표 실패 안내가 보이고 결과 화면(공유 store)은 그대로다", async () => {
    useMock("get-messages.200-restore-pending.json");
    const env = await mountMock();
    act(() => env.store.acceptPlanView(planView(12)));
    const before = env.store.getState();
    expect(pendings()).toHaveLength(1);

    useMock("get-messages.200-failed.json");
    await advance(1000);
    expect(pendings()).toHaveLength(0);
    const failed = bubble(1031)!;
    expect(failed.getAttribute("data-message-type")).toBe("FAILURE");
    expect(failed.getAttribute("data-status")).toBe("FAILED");
    expect(failed.textContent).toContain(copy["chat.failed"]);
    expect(env.store.getState()).toBe(before);
    expect(env.requests.some((r) => r.path.startsWith("/api/plan"))).toBe(false);
  });

  it("get-messages.200-clarify: 되묻는 문장이 보이고 비교 결과는 보이지 않는다", async () => {
    useMock("get-messages.200-clarify.json");
    await mountMock();
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("CLARIFY");
    expect(bubble(1031)?.textContent).toContain("얼마로 바꿔 볼지 알려 주세요");
    expect(screen.queryByTestId("chat-comparison")).toBeNull();
    expect(screen.queryByText("내 계획으로 적용")).toBeNull();
  });

  it("get-messages.200-out-of-scope: 문구표 범위 밖 안내가 말풍선으로 보인다", async () => {
    useMock("get-messages.200-out-of-scope.json");
    await mountMock();
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("OUT_OF_SCOPE");
    expect(bubble(1031)?.textContent).toContain(copy["outOfScope"]);
  });
});

describe("목 모드: 질문 보내기(F-1)", () => {
  it("202 -> 사용자 말풍선·대기 표시 -> 폴링으로 답이 오면 대기 표시가 사라진다", async () => {
    const env = await mountMock();
    useMock("get-messages.seq-question-recalc.json"); // 보낸 뒤 폴링이 받을 응답
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "월 50만원으로 하면 어때요?" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await settle();

    expect(env.requests.filter((r) => r.method === "POST")).toEqual([
      { method: "POST", path: "/api/chat/messages", body: { text: "월 50만원으로 하면 어때요?" } },
    ]);
    expect(bubble(1030)?.textContent).toContain("월 50만원으로 하면 어때요?");
    expect(pendings()).toHaveLength(1);
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(true);

    await advance(1000); // 목: PENDING 그대로
    expect(pendings()).toHaveLength(1);
    await advance(1000); // 목: RECALC_RESULT
    expect(pendings()).toHaveLength(0);
    expect(bubble(1031)?.getAttribute("data-message-type")).toBe("RECALC_RESULT");
    expect(screen.queryAllByTestId("chat-message")).toHaveLength(2);
  });

  it("post-messages.409-answer-pending: 보내기가 막히고 글이 남는다", async () => {
    await mountMock();
    useMock("post-messages.409-answer-pending.json");
    useMock("get-messages.200-restore-pending.json"); // 재개된 폴링이 받을 응답
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await settle();
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe("질문");
    await advance(1000);
    expect(pendings()).toHaveLength(1);
    expect((screen.getByTestId("chat-send") as HTMLButtonElement).disabled).toBe(true);
  });

  it.each([
    ["post-messages.422-required.json", copy["validation.REQUIRED"]],
  ])("%s: 입력창 아래 안내가 보이고 글이 남는다", async (file, message) => {
    await mountMock();
    useMock(file);
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await settle();
    expect(screen.getByTestId("chat-input-error").textContent).toBe(message);
    expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe("질문");
  });

  it.each(["post-messages.500.json", "post-messages.400.json"])("%s: 문구표 토스트가 뜨고 글이 남는다", async (file) => {
    await mountMock();
    useMock(file);
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await settle();
    expect(screen.getByTestId("chat-toast").textContent).toBe(copy["chat.sendFailed"]);
    expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe("질문");
  });

  it("401이면 로그인 화면으로 가고 토스트는 없다", async () => {
    const env = await mountMock();
    useMock("common.401-unauthenticated.json");
    fireEvent.change(screen.getByTestId("chat-input"), { target: { value: "질문" } });
    fireEvent.click(screen.getByTestId("chat-send"));
    await settle();
    expect(env.navigations).toEqual([LOGIN_PATH]);
    expect(screen.queryByTestId("chat-toast")).toBeNull();
  });
});

describe("목 모드: 폴링 실패와 401", () => {
  it("폴링 중 401을 받으면 래퍼가 공유 상태를 비우고 로그인으로 보내며 폴링이 멈춘다(실패 안내 없음)", async () => {
    useMock("get-messages.200-restore-pending.json");
    const env = await mountMock();
    act(() => env.store.acceptPlanView(planView(12)));
    expect(env.store.getState().revision).toBe(12);

    useMock("get-messages.401.json");
    await advance(1000);
    expect(env.navigations).toEqual([LOGIN_PATH]);
    expect(env.store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
    const count = chatGets(env).length;
    await advance(10_000);
    expect(chatGets(env)).toHaveLength(count);
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
  });

  it("get-messages.seq-500x3: 연속 3회 500이면 폴링이 멈추고 실패 안내가 한 번 보인다", async () => {
    useMock("get-messages.seq-500x3.json");
    const env = await mountMock();
    await advance(1000);
    await advance(1000);
    await advance(10_000);
    expect(chatGets(env)).toHaveLength(3);
    const notices = screen.getAllByTestId("chat-polling-failed");
    expect(notices).toHaveLength(1);
    expect(notices[0].textContent).toBe(copy["chat.pollingFailed"]);
  });
});
