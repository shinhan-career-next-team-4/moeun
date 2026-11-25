// Issue #224: 패널 문구 상수는 copy/copy.ko.yml의 chat.* 값과 같다(프론트에는 문구표 로더가 없어 복사해 둔 값이다).
import { describe, expect, it } from "vitest";
import { COPY } from "../copy";
import { copy } from "./helpers";

const PANEL_KEYS = [
  "panelTitle",
  "panelNotice",
  "panelOpen",
  "panelClose",
  "panelUnread",
  "emptyGuide",
  "typing",
  "inputPlaceholder",
  "send",
  "retryExplanation",
  "retryFailed",
] as const;

describe("패널 문구", () => {
  it.each(PANEL_KEYS)("%s가 문구표 chat.%s와 같다", (key) => {
    expect(copy[`chat.${key}`], key).toBeTruthy();
    expect(COPY[key]).toBe(copy[`chat.${key}`]);
  });

  it("예시 질문 안내와 버튼 문구에 '추천'이 없다", () => {
    for (const key of PANEL_KEYS) expect(COPY[key], key).not.toContain("추천");
  });
});
