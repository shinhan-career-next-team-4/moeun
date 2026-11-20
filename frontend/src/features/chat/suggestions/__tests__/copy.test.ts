// Issue #69 I-1: 예시 문구 상수는 copy/copy.ko.yml의 chat.exampleQuestion 값과 같은 순서로 일치한다.
import { describe, expect, it } from "vitest";
import { copy } from "../../__tests__/helpers";
import { EXAMPLE_QUESTIONS } from "../copy";

describe("예시 문구 상수", () => {
  it("문구표 chat.exampleQuestion 값과 순서까지 일치한다", () => {
    const expected = Object.entries(copy)
      .filter(([key]) => key.startsWith("chat.exampleQuestion."))
      .map(([, value]) => value);
    expect([...EXAMPLE_QUESTIONS]).toEqual(expected);
  });
});
