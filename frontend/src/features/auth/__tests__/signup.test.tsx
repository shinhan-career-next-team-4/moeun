// @vitest-environment jsdom
// Issue #460 회원가입 화면(FR-AUTH-04, D-107). API는 #459 계약 P-7 POST /api/auth/signup.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { authSync, planStore } from "../../../api";
import NavigateBridge from "../../../app/NavigateBridge";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH, SIGNUP_PATH } from "../../../app/routes";
import { COPY } from "../../../shared/copy/copy";
import { LoginPage } from "../LoginPage";
import { SignupPage } from "../SignupPage";

function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": contentType },
  });
}

function problem(status: number, code: string, errors: unknown[] = []): Response {
  return jsonResponse(status, { type: "about:blank", title: "t", status, code, errors }, "application/problem+json");
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <NavigateBridge />
      <Routes>
        <Route path={LOGIN_PATH} element={<LoginPage />} />
        <Route path={SIGNUP_PATH} element={<SignupPage />} />
        <Route path={INPUT_PATH} element={<div>입력화면</div>} />
        <Route path={RESULT_PATH} element={<div>결과화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

const loginIdInput = () => screen.getByLabelText("아이디") as HTMLInputElement;
const passwordInput = () => screen.getByLabelText("비밀번호") as HTMLInputElement;
const confirmInput = () => screen.getByLabelText("비밀번호 확인") as HTMLInputElement;
const submitButton = () => screen.getByRole("button", { name: "가입하기" }) as HTMLButtonElement;

function fill(loginId: string, password: string, confirm: string) {
  fireEvent.change(loginIdInput(), { target: { value: loginId } });
  fireEvent.change(passwordInput(), { target: { value: password } });
  fireEvent.change(confirmInput(), { target: { value: confirm } });
}

describe("SignupPage (#460, FR-AUTH-04)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe("AC-1 로그인 화면과 회원가입 화면을 오간다", () => {
    it("로그인 화면의 '회원가입' 링크를 누르면 /signup 회원가입 화면으로 간다", async () => {
      renderAt(LOGIN_PATH);
      fireEvent.click(screen.getByRole("link", { name: "회원가입" }));
      expect(await screen.findByRole("heading", { name: "회원가입" })).toBeTruthy();
    });

    it("회원가입 화면의 '로그인' 링크를 누르면 로그인 화면으로 돌아간다", async () => {
      renderAt(SIGNUP_PATH);
      fireEvent.click(screen.getByRole("link", { name: "로그인" }));
      expect(await screen.findByRole("heading", { name: "로그인" })).toBeTruthy();
    });

    it("회원가입 경로는 /signup 이다", () => {
      expect(SIGNUP_PATH).toBe("/signup");
    });
  });

  describe("AC-2 입력 칸과 규칙 안내", () => {
    it("아이디·비밀번호·비밀번호 확인 칸이 있고, 칸 아래에 규칙을 안내한다", () => {
      renderAt(SIGNUP_PATH);
      expect(loginIdInput().type).toBe("text");
      expect(passwordInput().type).toBe("password");
      expect(confirmInput().type).toBe("password");
      expect(screen.getByText(COPY["auth.loginIdRule"])).toBeTruthy();
      expect(screen.getByText(COPY["auth.passwordRule"])).toBeTruthy();
      expect(COPY["auth.loginIdRule"]).toContain("영문·숫자 4~20자");
      expect(COPY["auth.passwordRule"]).toContain("8~64자");
    });

    it("규칙 안내는 해당 칸의 설명(aria-describedby)으로 연결된다", () => {
      renderAt(SIGNUP_PATH);
      const idHint = screen.getByText(COPY["auth.loginIdRule"]);
      const pwHint = screen.getByText(COPY["auth.passwordRule"]);
      expect(loginIdInput().getAttribute("aria-describedby")).toContain(idHint.id);
      expect(passwordInput().getAttribute("aria-describedby")).toContain(pwHint.id);
    });

    it("화면에 들어올 때는 API를 부르지 않는다", async () => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);
      await new Promise((r) => setTimeout(r, 10));
      expect(fetchSpy).not.toHaveBeenCalled();
    });
  });

  describe("AC-3 가입 성공은 로그인 성공과 같은 흐름이다", () => {
    it("아이디·비밀번호만 보내고(확인 값은 보내지 않는다) 다른 탭에 login을 알린 뒤, 계획이 없으면(409 NO_PLAN) 입력 화면으로 간다", async () => {
      const notify = vi.spyOn(authSync, "notifyAuthChanged");
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(200, {}))
        .mockResolvedValueOnce(problem(409, "NO_PLAN"));
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect(await screen.findByText("입력화면")).toBeTruthy();
      expect(fetchSpy).toHaveBeenNthCalledWith(
        1,
        "/api/auth/signup",
        expect.objectContaining({ method: "POST", credentials: "include" }),
      );
      expect(JSON.parse(fetchSpy.mock.calls[0][1].body)).toEqual({ loginId: "saver01", password: "pass-1234" });
      expect(fetchSpy).toHaveBeenNthCalledWith(
        2,
        "/api/plan",
        expect.objectContaining({ method: "GET", credentials: "include" }),
      );
      expect(notify).toHaveBeenCalledTimes(1);
      expect(notify).toHaveBeenCalledWith("login");
    });

    it("계획이 있으면(P-4 200) 결과 화면으로 간다", async () => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValueOnce(jsonResponse(200, {}))
          .mockResolvedValueOnce(jsonResponse(200, { revision: 12, calcRunId: 540, result: { status: "CALCULATED" } })),
      );
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect(await screen.findByText("결과화면")).toBeTruthy();
    });

    it("가입 뒤 계획 조회가 실패해도(500) 다시 가입하게 두지 않고 입력 화면으로 넘긴다", async () => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValueOnce(jsonResponse(200, {}))
          .mockResolvedValueOnce(problem(500, "INTERNAL_ERROR")),
      );
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect(await screen.findByText("입력화면")).toBeTruthy();
    });

    it("가입 실패(409)는 다른 탭에 알리지 않는다", async () => {
      const notify = vi.spyOn(authSync, "notifyAuthChanged");
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(409, "DUPLICATE_LOGIN_ID")));
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      await screen.findByText(COPY["auth.duplicateLoginId"]);
      expect(notify).not.toHaveBeenCalled();
    });
  });

  describe("AC-4 (예외) 비밀번호와 확인이 다르다", () => {
    it("서버로 보내지 않고 확인 칸에 안내한다", async () => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-12345");
      fireEvent.click(submitButton());

      const message = await screen.findByText(COPY["auth.passwordMismatch"]);
      expect(confirmInput().getAttribute("aria-invalid")).toBe("true");
      expect(confirmInput().getAttribute("aria-describedby")).toContain(message.id);
      expect(passwordInput().getAttribute("aria-invalid")).toBeNull();
      expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("확인 칸이 비었으면 REQUIRED 안내를 하고 보내지 않는다", async () => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "");
      fireEvent.click(submitButton());

      expect(await screen.findByText(COPY["validation.REQUIRED"])).toBeTruthy();
      expect(confirmInput().getAttribute("aria-invalid")).toBe("true");
      expect(fetchSpy).not.toHaveBeenCalled();
    });
  });

  describe("AC-5 (예외) 형식 오류는 보내기 전에, 서버 422는 해당 칸에", () => {
    it("빈 값으로 제출하면 세 칸 모두 REQUIRED 안내를 하고 보내지 않는다", async () => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fireEvent.click(submitButton());

      expect(await screen.findAllByText(COPY["validation.REQUIRED"])).toHaveLength(3);
      expect(fetchSpy).not.toHaveBeenCalled();
    });

    it.each(["abc", "a".repeat(21), "saver_01", "세이버0001", "saver 01"])(
      "아이디 %j 는 형식 오류로 아이디 칸에 안내하고 보내지 않는다",
      async (loginId) => {
        const fetchSpy = vi.fn();
        vi.stubGlobal("fetch", fetchSpy);
        renderAt(SIGNUP_PATH);

        fill(loginId, "pass-1234", "pass-1234");
        fireEvent.click(submitButton());

        expect(await screen.findByText(COPY["auth.loginIdInvalidFormat"])).toBeTruthy();
        expect(loginIdInput().getAttribute("aria-invalid")).toBe("true");
        expect(fetchSpy).not.toHaveBeenCalled();
      },
    );

    it.each(["p".repeat(7), "p".repeat(65)])("비밀번호 길이 %#(7자·65자)는 형식 오류로 비밀번호 칸에 안내하고 보내지 않는다", async (password) => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill("saver01", password, password);
      fireEvent.click(submitButton());

      expect(await screen.findByText(COPY["auth.passwordInvalidFormat"])).toBeTruthy();
      expect(passwordInput().getAttribute("aria-invalid")).toBe("true");
      expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("비밀번호가 UTF-8 72바이트를 넘으면(BCrypt 한도, \"가\" 25자 = 75바이트) 형식 오류로 보내지 않는다", async () => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      const password = "가".repeat(25);
      fill("saver01", password, password);
      fireEvent.click(submitButton());

      expect(await screen.findByText(COPY["auth.passwordInvalidFormat"])).toBeTruthy();
      expect(passwordInput().getAttribute("aria-invalid")).toBe("true");
      expect(fetchSpy).not.toHaveBeenCalled();
    });

    it.each([
      ["abcd", "p".repeat(8)],
      ["A".repeat(20), "p".repeat(64)],
      ["saver01", "가".repeat(24)],
    ])("경계값 아이디 %j·비밀번호(8자·64자·72바이트)는 보낸다", async (loginId, password) => {
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(200, {}))
        .mockResolvedValueOnce(problem(409, "NO_PLAN"));
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill(loginId, password, password);
      fireEvent.click(submitButton());

      await screen.findByText("입력화면");
      expect(JSON.parse(fetchSpy.mock.calls[0][1].body)).toEqual({ loginId, password });
    });

    it("아이디 앞뒤 공백은 지우고 검사·전송한다(로그인 화면과 같다)", async () => {
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(200, {}))
        .mockResolvedValueOnce(problem(409, "NO_PLAN"));
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill("  saver01 ", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      await screen.findByText("입력화면");
      expect(JSON.parse(fetchSpy.mock.calls[0][1].body).loginId).toBe("saver01");
    });

    it("서버 422 INVALID_FORMAT(loginId)·REQUIRED(password)를 해당 칸에 보인다", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          problem(422, "VALIDATION_FAILED", [
            { field: "loginId", reason: "INVALID_FORMAT" },
            { field: "password", reason: "REQUIRED" },
          ]),
        ),
      );
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect(await screen.findByText(COPY["auth.loginIdInvalidFormat"])).toBeTruthy();
      expect(screen.getByText(COPY["validation.REQUIRED"])).toBeTruthy();
      expect(loginIdInput().getAttribute("aria-invalid")).toBe("true");
      expect(passwordInput().getAttribute("aria-invalid")).toBe("true");
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("서버 422 INVALID_FORMAT(password)는 비밀번호 칸에 보인다", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(problem(422, "VALIDATION_FAILED", [{ field: "password", reason: "INVALID_FORMAT" }])),
      );
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect(await screen.findByText(COPY["auth.passwordInvalidFormat"])).toBeTruthy();
      expect(passwordInput().getAttribute("aria-invalid")).toBe("true");
    });
  });

  describe("AC-6 (예외) 409 DUPLICATE_LOGIN_ID", () => {
    it("아이디 칸에 '이미 사용 중인 아이디입니다'를 안내한다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(409, "DUPLICATE_LOGIN_ID")));
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      const message = await screen.findByText("이미 사용 중인 아이디입니다");
      expect(loginIdInput().getAttribute("aria-invalid")).toBe("true");
      expect(loginIdInput().getAttribute("aria-describedby")).toContain(message.id);
      expect(COPY["auth.duplicateLoginId"]).toBe("이미 사용 중인 아이디입니다");
    });
  });

  describe("AC-7 (예외) 400·500·네트워크 오류는 화면 위 안내, 입력은 유지", () => {
    it.each([
      ["400 MALFORMED_REQUEST", () => problem(400, "MALFORMED_REQUEST"), COPY["auth.signupFailed"]],
      ["500 INTERNAL_ERROR", () => problem(500, "INTERNAL_ERROR"), COPY["auth.signupFailed"]],
      ["502 HTML", () => new Response("<html></html>", { status: 502, headers: { "Content-Type": "text/html" } }), COPY["common.networkError"]],
    ])("%s", async (_name, response, expected) => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response()));
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect((await screen.findByRole("alert")).textContent).toBe(expected);
      expect(loginIdInput().value).toBe("saver01");
      expect(passwordInput().value).toBe("pass-1234");
      expect(confirmInput().value).toBe("pass-1234");
      expect(submitButton().disabled).toBe(false);
    });

    it("fetch 자체가 실패하면(네트워크) 네트워크 안내", async () => {
      vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());

      expect((await screen.findByRole("alert")).textContent).toBe(COPY["common.networkError"]);
      expect(loginIdInput().value).toBe("saver01");
      expect(passwordInput().value).toBe("pass-1234");
    });
  });

  describe("AC-8 보내는 중에는 가입 버튼이 눌리지 않는다", () => {
    it("응답 전에 두 번 눌러도 한 번만 보내고, 버튼은 비활성이다", async () => {
      let resolveSignup!: (response: Response) => void;
      const fetchSpy = vi
        .fn()
        .mockImplementationOnce(() => new Promise<Response>((resolve) => (resolveSignup = resolve)))
        .mockResolvedValueOnce(problem(409, "NO_PLAN"));
      vi.stubGlobal("fetch", fetchSpy);
      renderAt(SIGNUP_PATH);

      fill("saver01", "pass-1234", "pass-1234");
      fireEvent.click(submitButton());
      await waitFor(() => expect(submitButton().disabled).toBe(true));
      fireEvent.click(submitButton());
      fireEvent.submit(submitButton().closest("form")!);
      expect(fetchSpy).toHaveBeenCalledTimes(1);

      resolveSignup(jsonResponse(200, {}));
      expect(await screen.findByText("입력화면")).toBeTruthy();
      expect(fetchSpy).toHaveBeenCalledTimes(2);
    });
  });
});
