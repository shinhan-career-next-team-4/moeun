// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import { LoginPage } from "../LoginPage";

function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": contentType },
  });
}

describe("LoginPage (#47, C12a-3)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderLogin() {
    return render(
      <MemoryRouter initialEntries={[LOGIN_PATH]}>
        <Routes>
          <Route path={LOGIN_PATH} element={<LoginPage />} />
          <Route path={INPUT_PATH} element={<div>입력화면</div>} />
          <Route path={RESULT_PATH} element={<div>결과화면</div>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it("빈 값으로 제출하면 REQUIRED 안내를 표시하고 API를 부르지 않는다", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    renderLogin();

    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findAllByText("값을 입력해 주세요")).toHaveLength(2);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("로그인 실패(401 INVALID_CREDENTIALS) 시 안내를 표시하고 비밀번호를 비운다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(401, { code: "INVALID_CREDENTIALS", errors: [] }, "application/problem+json"),
      ),
    );
    renderLogin();

    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "demo01" } });
    const pwInput = screen.getByLabelText("비밀번호") as HTMLInputElement;
    fireEvent.change(pwInput, { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect((await screen.findByRole("alert")).textContent).toContain("아이디 또는 비밀번호가 맞지 않아요");
    expect(pwInput.value).toBe("");
  });

  it("로그인 성공 후 계획이 있으면(P-4 200) 결과 화면으로 이동한다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, {}))
      .mockResolvedValueOnce(
        jsonResponse(200, { revision: 12, calcRunId: 540, result: { status: "CALCULATED" } }),
      );
    vi.stubGlobal("fetch", fetchSpy);
    renderLogin();

    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "demo01" } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "pw-123456" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => {
      expect(screen.getByText("결과화면")).toBeTruthy();
    });
    expect(fetchSpy).toHaveBeenNthCalledWith(
      1,
      "/api/auth/login",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
    expect(fetchSpy).toHaveBeenNthCalledWith(
      2,
      "/api/plan",
      expect.objectContaining({ method: "GET", credentials: "include" }),
    );
  });
});
