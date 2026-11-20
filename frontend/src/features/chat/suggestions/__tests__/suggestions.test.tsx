// @vitest-environment jsdom
// Issue #69 AC-1~AC-4: 질문 예시 버튼 — 문구표에서 읽은 예시가 대화창에 보이고, 누르면 그 문장이 질문으로 전송된다.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deferred } from "../../../../api/__tests__/helpers";
import { ChatWindow } from "../../index";
import { copy, flush, makeDeps, makeFakeApi, page, pendingMsg, userMsg } from "../../__tests__/helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const PREFIX = "chat.exampleQuestion.";
const ETF_RISK = "ETF에는 어떤 위험이 있어요?";
const ACCEPTED = { userMessageId: 1030, assistantMessageId: 1031 };

// 문구표(copy/copy.ko.yml)의 chat.exampleQuestion 값 전부. 버튼 문장의 기준이다.
const questions = Object.entries(copy)
  .filter(([key]) => key.startsWith(PREFIX))
  .map(([, value]) => value);

async function mount(replies: Parameters<typeof makeFakeApi>[0]) {
  const api = makeFakeApi(replies);
  const { deps } = makeDeps(api);
  render(<ChatWindow deps={deps} />);
  await flush();
  return api;
}

const exampleButton = (text: string) => screen.getByRole("button", { name: text }) as HTMLButtonElement;
const input = () => screen.getByTestId("chat-input") as HTMLTextAreaElement;
const bubbles = () => screen.queryAllByTestId("chat-message");

describe("AC-4 예시 문구", () => {
  it("문구표에 ETF 위험 질문이 chat.exampleQuestion 아래 새 키로 있고 기존 5개 키도 그대로다", () => {
    expect(copy["chat.exampleQuestion.etfRisk"]).toBe(ETF_RISK);
    for (const key of ["goalAmount", "monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths"]) {
      expect(copy[PREFIX + key], key).toBeTruthy();
    }
    expect(questions).toHaveLength(6);
  });

  it("어떤 예시 문장에도 '추천'과 금액·변화량 표현이 없고 해요체다", () => {
    for (const text of questions) {
      expect(text, text).not.toContain("추천");
      expect(text, text).not.toMatch(/\d+\s*(만\s*원|원|%|퍼센트)|늘리면|줄이면|올리면|내리면/);
      expect(text, text).toMatch(/요[?.]?$/);
    }
  });
});

describe("AC-1 대화창을 열면 예시 버튼이 보인다", () => {
  it("문구표의 예시 문장이 모두 버튼 글자로 그대로 보인다", async () => {
    await mount({ get: [page([], false)] });
    for (const text of questions) {
      expect(exampleButton(text).textContent, text).toBe(text);
    }
    expect(exampleButton(ETF_RISK)).toBeTruthy();
  });

  it("화면에 보이는 모든 글에 '추천'이 없다(고지문 제외)", async () => {
    await mount({ get: [page([], false)] });
    for (const text of questions) {
      expect(exampleButton(text).textContent).not.toContain("추천");
    }
  });
});

describe("AC-2 예시 버튼을 누르면 그 문장이 질문으로 전송된다", () => {
  it("버튼 문장이 그대로 POST /api/chat/messages 본문 text로 한 번 가고, 수락되면 USER 메시지로 보인다", async () => {
    const api = await mount({ get: [page([], false)], post: [ACCEPTED] });
    fireEvent.click(exampleButton(questions[1]));
    await flush();
    expect(api.posts()).toHaveLength(1);
    expect(api.posts()[0]).toMatchObject({ method: "POST", path: "/api/chat/messages", body: { text: questions[1] } });
    const user = bubbles().find((el) => el.getAttribute("data-message-id") === "1030");
    expect(user?.getAttribute("data-role")).toBe("USER");
    expect(user?.textContent).toContain(questions[1]);
  });

  it("ETF 위험 예시도 그 문장 그대로 전송된다", async () => {
    const api = await mount({ get: [page([], false)], post: [ACCEPTED] });
    fireEvent.click(exampleButton(ETF_RISK));
    await flush();
    expect(api.posts()).toHaveLength(1);
    expect(api.posts()[0].body).toEqual({ text: ETF_RISK });
  });
});

describe("AC-3 전송 중·답변 대기 중에는 예시 버튼이 막힌다", () => {
  it("전송 중에는 모든 예시 버튼이 비활성이고 눌러도 POST가 더 가지 않는다", async () => {
    const post = deferred<unknown>();
    const api = await mount({ get: [page([], false)], post: [post.promise] });
    fireEvent.click(exampleButton(questions[0]));
    await flush();
    expect(api.posts()).toHaveLength(1);
    for (const text of questions) expect(exampleButton(text).disabled, text).toBe(true);
    fireEvent.click(exampleButton(questions[2]));
    await flush();
    expect(api.posts()).toHaveLength(1);
    await act(async () => {
      post.resolve(ACCEPTED);
    });
    await flush();
  });

  it("답변 대기 중(answerPending=true)에는 예시 버튼이 비활성이고 눌러도 POST가 없다", async () => {
    const api = await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true, true)] });
    for (const text of questions) expect(exampleButton(text).disabled, text).toBe(true);
    fireEvent.click(exampleButton(questions[0]));
    await flush();
    expect(api.posts()).toHaveLength(0);
  });

  it("설명 대기만 있으면(answerPending=false) 예시 버튼은 눌린다", async () => {
    const api = await mount({ get: [page([userMsg(1030), pendingMsg(1031)], true, false)], post: [ACCEPTED] });
    for (const text of questions) expect(exampleButton(text).disabled, text).toBe(false);
    fireEvent.click(exampleButton(questions[0]));
    await flush();
    expect(api.posts()).toHaveLength(1);
  });

  it("입력창에 써 둔 글은 예시 버튼으로 보내도 지워지지 않고, 보낸 것은 버튼 문장이다", async () => {
    const api = await mount({ get: [page([], false)], post: [ACCEPTED] });
    fireEvent.change(input(), { target: { value: "써 두던 질문" } });
    fireEvent.click(exampleButton(questions[3]));
    await flush();
    expect(api.posts()).toHaveLength(1);
    expect(api.posts()[0].body).toEqual({ text: questions[3] });
    expect(input().value).toBe("써 두던 질문");
  });
});
