// @vitest-environment jsdom
// Issue #52 AC-1~AC-3, AC-8(화면), AC-10~AC-13, AC-19: 대화창 화면(ChatWindow) — 말풍선, 대기 표시, 입력·전송 규칙, 실패 안내, 고지.
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred } from "../../../api/__tests__/helpers";
import { ChatWindow } from "../index";
import {
  copy,
  doneMsg,
  failedMsg,
  flush,
  makeDeps,
  makeFakeApi,
  makePlanStore,
  malformed,
  networkError,
  page,
  pendingMsg,
  pollPath,
  problem,
  serverError,
  tick,
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
const ACCEPTED = { userMessageId: 1030, assistantMessageId: 1031 };

async function mount(replies: Parameters<typeof makeFakeApi>[0], planStore = makePlanStore()) {
  const api = makeFakeApi(replies);
  const { deps } = makeDeps(api, planStore);
  const view = render(<ChatWindow deps={deps} />);
  await flush();
  return { api, planStore, view };
}

const input = () => screen.getByTestId("chat-input") as HTMLTextAreaElement | HTMLInputElement;
const sendButton = () => screen.getByTestId("chat-send") as HTMLButtonElement;
const type = (value: string) => fireEvent.change(input(), { target: { value } });
const send = async () => {
  fireEvent.click(sendButton());
  await flush();
};
const bubbles = () => screen.queryAllByTestId("chat-message");
const bubble = (id: number) => bubbles().find((el) => el.getAttribute("data-message-id") === String(id));
const pendings = () => screen.queryAllByTestId("chat-pending");

describe("AC-1 질문을 보내고 기다리는 동안", () => {
  it("대기 표시가 보이고 보내기 버튼이 막힌다. 답이 오면 대기 표시가 사라지고 버튼이 풀린다", async () => {
    const post = deferred<unknown>();
    const { api } = await mount({
      get: [
        page([], false),
        page([userMsg(1030), pendingMsg(1031)], true),
        page([doneMsg(1031, "RECALC_RESULT", "월 저축액을 바꾸면 달성해요")], false),
      ],
      post: [post.promise],
    });
    expect(pendings()).toHaveLength(0);

    type("월 50만원으로 하면 어때요?");
    expect(sendButton().disabled).toBe(false);
    await send();

    // 전송 중(F-1 응답 전): 다시 보낼 수 없다
    expect(sendButton().disabled).toBe(true);
    fireEvent.click(sendButton());
    await flush();
    expect(api.posts()).toHaveLength(1);
    expect(api.posts()[0]).toMatchObject({
      method: "POST",
      path: "/api/chat/messages",
      body: { text: "월 50만원으로 하면 어때요?" },
    });

    // 202: 사용자 말풍선을 바로 그리고 대기 표시를 보인다
    await act(async () => {
      post.resolve(ACCEPTED);
    });
    await flush();
    expect(bubble(1030)?.textContent).toContain("월 50만원으로 하면 어때요?");
    expect(bubble(1030)?.getAttribute("data-role")).toBe("USER");
    expect(pendings()).toHaveLength(1);
    expect(pendings()[0].getAttribute("data-message-id")).toBe("1031");
    expect(input().value).toBe(""); // 보낸 글은 비운다
    type("다음 질문");
    expect(sendButton().disabled).toBe(true); // answerPending 동안 막힘

    // 폴링: 1초 뒤 afterId = PENDING id − 1
    await tick(1000);
    expect(api.gets()[1].path).toBe(pollPath(1030));
    expect(bubbles()).toHaveLength(1); // 같은 메시지를 다시 받아도 중복 말풍선이 없다
    expect(pendings()).toHaveLength(1);

    await tick(1000);
    expect(pendings()).toHaveLength(0);
    expect(bubble(1031)?.textContent).toContain("월 저축액을 바꾸면 달성해요");
    expect(bubbles()).toHaveLength(2);
    expect(sendButton().disabled).toBe(false);

    await tick(10_000);
    expect(api.gets()).toHaveLength(3); // hasPending=false라 멈췄다
  });

  it("새로고침 뒤(진입 때 복원에 answerPending=true)에도 보내기 버튼이 막혀 있다", async () => {
    await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true, true)] });
    type("글을 써 둠");
    expect(pendings()).toHaveLength(1);
    expect(sendButton().disabled).toBe(true);
  });

  it("설명 대기만 있으면(answerPending=false) 대기 표시는 보여도 보내기는 막지 않는다", async () => {
    await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true, false)] });
    type("질문");
    expect(pendings()).toHaveLength(1);
    expect(sendButton().disabled).toBe(false);
  });
});

describe("AC-2 FAILED 메시지", () => {
  it("폴링으로 받으면 실패 안내가 대화창에 보이고 결과 화면(공유 계획 store)은 그대로다", async () => {
    const { planStore, api } = await mount({
      get: [page([userMsg(1030), pendingMsg(1031)], true), page([failedMsg(1031)], false)],
    });
    const before = planStore.getState();
    expect(pendings()).toHaveLength(1);

    await tick(1000);
    const failed = bubble(1031)!;
    expect(failed.textContent).toContain(copy["chat.failed"]);
    expect(failed.getAttribute("data-message-type")).toBe("FAILURE");
    expect(failed.getAttribute("data-status")).toBe("FAILED");
    expect(pendings()).toHaveLength(0);
    expect(planStore.getState()).toBe(before);
    expect(api.calls.filter((c) => c.path.startsWith("/api/plan"))).toHaveLength(0);
  });
});

describe("AC-3 CLARIFY·OUT_OF_SCOPE 말풍선", () => {
  it("CLARIFY는 되묻는 문장만 보이고 비교 결과(카드·적용 버튼)는 보이지 않는다", async () => {
    const sentence = "얼마로 바꿔 볼지 알려 주세요. 바꿀 수 있는 조건은 월 저축액, 저축 비중, 적금 금리, 목표 기간이에요.";
    await mount({ get: [page([userMsg(1030, "월 저축액을 조금 늘려서 보면 어때요?"), doneMsg(1031, "CLARIFY", sentence)], false)] });
    const clarify = bubble(1031)!;
    expect(clarify.getAttribute("data-message-type")).toBe("CLARIFY");
    expect(clarify.getAttribute("data-role")).toBe("ASSISTANT");
    expect(clarify.textContent).toContain(sentence);
    expect(screen.queryByTestId("chat-comparison")).toBeNull();
    expect(screen.queryByText("내 계획으로 적용")).toBeNull();
  });

  it("OUT_OF_SCOPE는 안내 문장이 말풍선으로 보인다", async () => {
    await mount({ get: [page([userMsg(1030, "오늘 날씨 어때요?"), doneMsg(1031, "OUT_OF_SCOPE", copy["outOfScope"])], false)] });
    const out = bubble(1031)!;
    expect(out.getAttribute("data-message-type")).toBe("OUT_OF_SCOPE");
    expect(out.textContent).toContain(copy["outOfScope"]);
  });
});

describe("AC-13 메시지 그리기 규칙 (C12b-3)", () => {
  it("COMPLETED·FAILED만 messageType별 말풍선이고, id 오름차순으로 그린다", async () => {
    const rows = [
      userMsg(1030, "질문"),
      doneMsg(1031, "ANSWER", "답변이에요"),
      doneMsg(1032, "EXPLANATION", "설명이에요"),
      doneMsg(1033, "RECALC_RESULT", "다시 계산했어요"),
      doneMsg(1034, "CLARIFY", "되물어요"),
      doneMsg(1035, "OUT_OF_SCOPE", "범위 밖이에요"),
      failedMsg(1036),
    ];
    await mount({ get: [page([...rows].reverse(), false)] });
    expect(bubbles().map((el) => el.getAttribute("data-message-id"))).toEqual(rows.map((r) => String(r.id)));
    expect(bubbles().map((el) => el.getAttribute("data-message-type"))).toEqual([
      "QUESTION",
      "ANSWER",
      "EXPLANATION",
      "RECALC_RESULT",
      "CLARIFY",
      "OUT_OF_SCOPE",
      "FAILURE",
    ]);
    for (const row of rows) expect(bubble(row.id)?.textContent).toContain(row.content);
  });

  it("status=PENDING(messageType=null, content=null)은 말풍선이 아니라 대기 표시다. 'null'이라는 글자를 그리지 않는다", async () => {
    await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true)] });
    expect(bubble(1031)).toBeUndefined();
    expect(pendings()).toHaveLength(1);
    expect(screen.getByTestId("chat-window").textContent).not.toMatch(/null|undefined/);
  });

  it("citations(배열 순서가 순위, rank 키 없음)가 있어도 본문이 그려지고, F-2에 없는 값(conditionChanges 등)은 그리지 않는다", async () => {
    const answer = {
      ...doneMsg(1031, "ANSWER", "근거가 있는 답변이에요"),
      citations: [
        { title: "첫째 자료", sourceUrl: "https://example.com/a", orgName: "예시운용" },
        { title: "둘째 자료", sourceUrl: "https://example.com/b", orgName: "예시자산" },
      ],
      conditionChanges: ["NOT-IN-CONTRACT-VALUE"],
    };
    await mount({ get: [page([userMsg(1030), answer], false)] });
    expect(bubble(1031)?.textContent).toContain("근거가 있는 답변이에요");
    expect(screen.getByTestId("chat-window").textContent).not.toContain("NOT-IN-CONTRACT-VALUE");
  });
});

describe("AC-10 입력창 글자 수", () => {
  it("maxLength가 2000이고, trim한 길이(UTF-16)가 1~2000일 때만 보낼 수 있다", async () => {
    await mount({ get: [page([], false)] });
    expect(input().getAttribute("maxlength")).toBe("2000");

    type("");
    expect(sendButton().disabled).toBe(true);
    type("   \n\t  ");
    expect(sendButton().disabled).toBe(true);
    type("가");
    expect(sendButton().disabled).toBe(false);
    type("가".repeat(2000));
    expect(sendButton().disabled).toBe(false);
    type("가".repeat(2001));
    expect(sendButton().disabled).toBe(true);
    // 앞뒤 공백은 길이에 세지 않는다
    type(`  ${"가".repeat(2000)}  `);
    expect(sendButton().disabled).toBe(false);
    // UTF-16 코드 단위: 😀는 2칸
    type("😀".repeat(1000));
    expect(sendButton().disabled).toBe(false);
    type("😀".repeat(1001));
    expect(sendButton().disabled).toBe(true);
  });

  it("서버에는 trim한 문자열을 보낸다", async () => {
    const { api } = await mount({ get: [page([], false)], post: [ACCEPTED] });
    type("  월 50만원  \n");
    await send();
    expect(api.posts()[0].body).toEqual({ text: "월 50만원" });
  });
});

describe("AC-11 F-1 422·400", () => {
  it("422 REQUIRED면 입력창 아래에 문구표 안내가 보이고 글은 지워지지 않는다", async () => {
    await mount({
      get: [page([], false)],
      post: [problem(422, "VALIDATION_FAILED", [{ field: "text", reason: "REQUIRED" }])],
    });
    type("질문");
    await send();
    expect(screen.getByTestId("chat-input-error").textContent).toBe(copy["validation.REQUIRED"]);
    expect(input().value).toBe("질문");
    expect(screen.queryByTestId("chat-toast")).toBeNull();
    expect(bubbles()).toHaveLength(0);
  });

  it("422 OUT_OF_RANGE면 단위 틀을 채운 범위 안내가 보이고 글은 지워지지 않는다", async () => {
    await mount({
      get: [page([], false)],
      post: [problem(422, "VALIDATION_FAILED", [{ field: "text", reason: "OUT_OF_RANGE", min: 1, max: 2000, unit: "CHAR" }])],
    });
    type("가".repeat(50));
    await send();
    const unit = (n: number) => copy["unit.char"].replace("{n}", String(n));
    const expected = copy["validation.OUT_OF_RANGE"].replace("{min}", unit(1)).replace("{max}", unit(2000));
    expect(screen.getByTestId("chat-input-error").textContent).toBe(expected);
    expect(input().value).toBe("가".repeat(50));
  });

  it("400 MALFORMED_REQUEST는 입력창 안내가 아니라 일반 실패 토스트이고 글이 유지된다", async () => {
    await mount({ get: [page([], false)], post: [malformed()] });
    type("질문");
    await send();
    expect(screen.getByTestId("chat-toast").textContent).toBe(copy["chat.sendFailed"]);
    expect(screen.queryByTestId("chat-input-error")).toBeNull();
    expect(input().value).toBe("질문");
  });
});

describe("AC-12 F-1 409 ANSWER_PENDING", () => {
  it("보내기 버튼이 비활성되고 폴링이 재개되며 입력창 글은 지워지지 않는다", async () => {
    const { api } = await mount({
      get: [
        page([], false),
        page([userMsg(1030), pendingMsg(1031)], true, true),
        page([doneMsg(1031, "ANSWER", "다른 탭의 질문에 대한 답")], false),
      ],
      post: [problem(409, "ANSWER_PENDING")],
    });
    type("질문");
    await send();
    expect(input().value).toBe("질문");
    expect(sendButton().disabled).toBe(true);

    // 가진 메시지가 없으면 afterId를 붙일 수 없다 -> 쿼리 없는 호출
    await tick(1000);
    expect(api.gets()).toHaveLength(2);
    expect(api.gets()[1].path).toBe(RESTORE);
    expect(sendButton().disabled).toBe(true); // answerPending=true
    expect(input().value).toBe("질문");

    await tick(1000);
    expect(api.gets()[2].path).toBe(pollPath(1030));
    expect(sendButton().disabled).toBe(false);
    expect(input().value).toBe("질문");
  });
});

describe("AC-12b F-1 500·네트워크 실패", () => {
  it.each([
    ["500 INTERNAL_ERROR", serverError],
    ["네트워크 오류", networkError],
  ])("%s면 문구표 '보내지 못했어요' 토스트가 뜨고 입력창 글이 유지된다", async (_name, make) => {
    await mount({ get: [page([], false)], post: [make()] });
    type("질문");
    await send();
    expect(screen.getByTestId("chat-toast").textContent).toBe(copy["chat.sendFailed"]);
    expect(input().value).toBe("질문");
    expect(sendButton().disabled).toBe(false); // 다시 보낼 수 있다
    expect(bubbles()).toHaveLength(0);
  });
});

describe("AC-8 연속 실패 안내와 재개 (화면)", () => {
  it("연속 3회 실패하면 문구표 실패 안내가 한 번 보이고, F-1을 다시 보내면 폴링이 재개된다", async () => {
    const { api } = await mount({
      get: [
        serverError(),
        serverError(),
        serverError(),
        page([userMsg(1030), pendingMsg(1031)], true),
        page([doneMsg(1031, "ANSWER", "답")], false),
      ],
      post: [ACCEPTED],
    });
    await tick(2000);
    expect(api.gets()).toHaveLength(3);
    const notices = screen.getAllByTestId("chat-polling-failed");
    expect(notices).toHaveLength(1);
    expect(notices[0].textContent).toBe(copy["chat.pollingFailed"]);
    await tick(10_000);
    expect(api.gets()).toHaveLength(3);
    expect(screen.getAllByTestId("chat-polling-failed")).toHaveLength(1);

    type("다시 질문");
    await send();
    expect(screen.queryByTestId("chat-polling-failed")).toBeNull();
    await tick(1000);
    expect(api.gets()).toHaveLength(4);
    expect(api.gets()[3].path).toBe(pollPath(1030));
    await tick(1000);
    expect(bubble(1031)?.textContent).toContain("답");
  });
});

describe("AC-19 고지 한 줄 (D-066, J-12f)", () => {
  it("메시지 없음·PENDING 중·실패 후에도 문구표 disclaimer 한 줄이 항상 하나 보인다", async () => {
    const empty = await mount({ get: [page([], false)] });
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
    empty.view.unmount();

    const waiting = await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true)] });
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
    waiting.view.unmount();

    await mount({ get: [serverError()] });
    await tick(5000);
    expect(screen.getAllByTestId("chat-polling-failed")).toHaveLength(1);
    expect(screen.getAllByTestId("chat-disclaimer")).toHaveLength(1);
    expect(screen.getByTestId("chat-disclaimer").textContent).toBe(copy["disclaimer"]);
  });

  it("고지 문장에 과거 데이터 고지와 'AI 설명은 참고용이며 계산 결과가 기준이에요'가 들어 있다(결과 화면 면책과 같은 키)", () => {
    expect(copy["disclaimer"]).toContain("과거 데이터");
    expect(copy["disclaimer"]).toContain("AI 설명은 참고용이며 계산 결과가 기준이에요");
    expect(copy["chat.disclaimer"]).toBeUndefined();
  });
});

describe("대화창 구조", () => {
  it("메시지 목록(chat-messages)은 비어 있어도 항상 있고 입력창과 보내기 버튼이 함께 있다", async () => {
    await mount({ get: [page([], false)] });
    const root = screen.getByTestId("chat-window");
    expect(within(root).getByTestId("chat-messages")).toBeTruthy();
    expect(within(root).getByTestId("chat-input")).toBeTruthy();
    expect(within(root).getByTestId("chat-send")).toBeTruthy();
  });
});
