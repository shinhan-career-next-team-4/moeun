// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { forgetDisplayName, readDisplayName, rememberDisplayName } from "../displayName";

describe("헤더에 보여 줄 로그인 아이디 (와이어프레임 02 헤더)", () => {
  afterEach(() => {
    forgetDisplayName();
  });

  it("기억한 아이디를 그대로 돌려주고, 잊으면 null이다", () => {
    expect(readDisplayName()).toBeNull();

    rememberDisplayName("demo");
    expect(readDisplayName()).toBe("demo");

    forgetDisplayName();
    expect(readDisplayName()).toBeNull();
  });

  it("sessionStorage가 막혀 있어도 던지지 않고 null을 돌려준다", () => {
    const original = Object.getOwnPropertyDescriptor(window, "sessionStorage");
    Object.defineProperty(window, "sessionStorage", {
      configurable: true,
      get() {
        throw new Error("blocked");
      },
    });

    expect(() => rememberDisplayName("demo")).not.toThrow();
    expect(readDisplayName()).toBeNull();
    expect(() => forgetDisplayName()).not.toThrow();

    if (original) Object.defineProperty(window, "sessionStorage", original);
  });
});
