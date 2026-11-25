// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { forgetDisplayName, rememberDisplayName } from "../../features/auth";
import { INPUT_PATH, LOGIN_PATH, SIGNUP_PATH } from "../routes";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );

describe("공통 레이아웃", () => {
  it.each([INPUT_PATH, LOGIN_PATH, SIGNUP_PATH, "/아직-없는-경로"])("%s 에서도 헤더(banner)와 본문 영역(main)이 보인다", (path) => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    renderAt(path);
    const header = screen.getByRole("banner");
    expect(header.textContent?.trim().length).toBeGreaterThan(0);
    expect(header.textContent).toContain("모은");
    expect(screen.getByRole("main")).toBeTruthy();
  });

  it("라우트 경로 상수: 로그인은 /login, 입력 화면 경로도 정해져 있다", () => {
    expect(LOGIN_PATH).toBe("/login");
    expect(typeof INPUT_PATH).toBe("string");
    expect(INPUT_PATH.startsWith("/")).toBe(true);
    expect(INPUT_PATH).not.toBe(LOGIN_PATH);
  });

  it("로그인 화면은 초기 진입 시 API를 부르지 않는다", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    renderAt(LOGIN_PATH);
    await new Promise((r) => setTimeout(r, 10));
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("회원가입 화면(/signup, #460)은 회원가입 양식을 보이고, 로그인 화면처럼 로그아웃 버튼이 없으며 API를 부르지 않는다", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    renderAt(SIGNUP_PATH);
    expect(screen.getByRole("heading", { name: "회원가입" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "로그아웃" })).toBeNull();
    await new Promise((r) => setTimeout(r, 10));
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("라우트가 바뀌어도 헤더는 하나뿐이다(레이아웃이 한 번만 감싼다)", () => {
    renderAt(LOGIN_PATH);
    expect(screen.getAllByRole("banner")).toHaveLength(1);
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  describe("데모 초기화", () => {
    beforeEach(() => rememberDisplayName("demo02"));
    afterEach(() => forgetDisplayName());

    /** 아이디를 연달아 세 번 누른 것처럼 click 이벤트의 연속 클릭 수(detail)를 1, 2, 3으로 보낸다. */
    function clickIdTimes(times: number) {
      const id = screen.getByText("demo02 님");
      for (let count = 1; count <= times; count += 1) fireEvent.click(id, { detail: count });
    }

    it("화면에는 초기화 버튼이 없다", () => {
      vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
      renderAt(INPUT_PATH);

      expect(screen.queryByRole("button", { name: "데모 초기화" })).toBeNull();
      expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("아이디를 한두 번 누르는 것으로는 초기화 확인이 뜨지 않는다", () => {
      vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
      renderAt(INPUT_PATH);

      clickIdTimes(2);

      expect(screen.queryByRole("dialog")).toBeNull();
    });

    function stubFetch(resetResponse: Response) {
      const calls: string[] = [];
      vi.stubGlobal(
        "fetch",
        vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
          const url = new URL(String(input), "http://localhost");
          calls.push(`${init?.method ?? "GET"} ${url.pathname}`);
          if (url.pathname === "/api/auth/demo-reset") return resetResponse;
          return new Response(JSON.stringify({ type: "about:blank", status: 409, code: "NO_PLAN", errors: [] }), {
            status: 409,
            headers: { "Content-Type": "application/problem+json" },
          });
        }),
      );
      return calls;
    }

    it("아이디를 세 번 누르면 바로 지우지 않고 확인을 먼저 묻는다", () => {
      const calls = stubFetch(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
      renderAt(INPUT_PATH);

      clickIdTimes(3);

      const dialog = screen.getByRole("dialog");
      expect(dialog.textContent).toContain("계획과 대화가 모두 지워져요. 초기화할까요?");
      expect(calls).not.toContain("POST /api/auth/demo-reset");
    });

    it("확인하면 초기화 API를 부르고, 취소하면 부르지 않는다", async () => {
      const calls = stubFetch(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
      renderAt(INPUT_PATH);

      clickIdTimes(3);
      fireEvent.click(screen.getByRole("button", { name: "취소" }));
      expect(calls).not.toContain("POST /api/auth/demo-reset");

      clickIdTimes(3);
      fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "초기화" }));
      await waitFor(() => expect(calls).toContain("POST /api/auth/demo-reset"));
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("초기화에 실패하면 안내가 보인다", async () => {
      stubFetch(
        new Response(JSON.stringify({ type: "about:blank", status: 500, code: "INTERNAL_ERROR", errors: [] }), {
          status: 500,
          headers: { "Content-Type": "application/problem+json" },
        }),
      );
      renderAt(INPUT_PATH);

      clickIdTimes(3);
      fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "초기화" }));

      const dialog = screen.getByRole("dialog");
      expect((await within(dialog).findByRole("alert")).textContent).toBe("초기화하지 못했어요. 다시 시도해 주세요");
    });
  });
});
