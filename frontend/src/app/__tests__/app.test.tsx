// @vitest-environment jsdom
// AC-1: 새로 clone한 사용자가 개발 서버를 켜면 빈 레이아웃(헤더 포함)이 뜬다.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { INPUT_PATH, LOGIN_PATH } from "../routes";

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

describe("AC-1 공통 레이아웃", () => {
  it.each(["/", LOGIN_PATH, "/아직-없는-경로"])("%s 에서도 헤더(banner)와 본문 영역(main)이 보인다", (path) => {
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

  it("빈 레이아웃은 API를 부르지 않는다(라우팅 가드·화면 데이터는 다른 Issue)", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    renderAt("/");
    await new Promise((r) => setTimeout(r, 10));
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("라우트가 바뀌어도 헤더는 하나뿐이다(레이아웃이 한 번만 감싼다)", () => {
    renderAt(LOGIN_PATH);
    expect(screen.getAllByRole("banner")).toHaveLength(1);
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });
});
