// Issue #52 규칙 점검: 화면 코드가 지켜야 할 것(fetch 직접 호출 금지, store는 settledStatus만 읽기, 옛 문구 키 금지, 범위).
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { copy, FEATURE_DIR } from "./helpers";

// #53 비교 카드(comparison/)는 계약상 store 쓰기(F-5 뒤 acceptRevision·loadPlan·acceptNoPlan)와 revision 구독을 맡으므로
// 이 읽기 전용 검사에서 뺀다(사용자 결정 2026-11-20 B1=A). comparison/의 규칙은 comparison/__tests__/boundary.test.ts가 본다.
const EXCLUDED_DIRS = new Set([path.join(FEATURE_DIR, "comparison")]);

function walk(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__tests__" || EXCLUDED_DIRS.has(full) ? [] : walk(full);
    return [full];
  });
}

const sources = walk(FEATURE_DIR).filter((file) => /\.(ts|tsx)$/.test(file));
const read = (file: string) => fs.readFileSync(file, "utf8");
const all = () => sources.map((file) => ({ file: path.relative(FEATURE_DIR, file), text: read(file) }));

describe("AC-20 공개 인터페이스 features/chat", () => {
  it("index 파일이 ChatWindow와 useChatPolling을 내보낸다", () => {
    const index = sources.find((file) => /[\\/]index\.tsx?$/.test(file) && path.dirname(file) === FEATURE_DIR);
    expect(index, "features/chat/index.ts(x)").toBeTruthy();
    const text = read(index!);
    expect(text).toMatch(/\bChatWindow\b/);
    expect(text).toMatch(/\buseChatPolling\b/);
  });
});

describe("AC-20 화면 코드 규칙 (frontend/README: 서버와는 api.request() 하나로만 통신)", () => {
  it("fetch·XMLHttpRequest·useMock·mockFetch를 직접 쓰지 않는다", () => {
    expect(sources.length).toBeGreaterThan(0);
    for (const { file, text } of all()) {
      const code = text.replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
      expect(code, file).not.toMatch(/\bfetch\s*\(/);
      expect(code, file).not.toMatch(/XMLHttpRequest|\buseMock\b|\bmockFetch\b/);
    }
  });

  it("초과 판단은 store의 settledStatus를 읽어서 한다. #53의 revision 구독(onRevisionChange)은 쓰지 않는다", () => {
    const text = all().map((s) => s.text).join("\n");
    expect(text).toContain("settledStatus");
    expect(text).not.toContain("onRevisionChange");
  });

  it("옛 문구 키 chat.disclaimer를 쓰지 않는다", () => {
    expect(sources.length).toBeGreaterThan(0);
    for (const { file, text } of all()) {
      expect(text, file).not.toContain("chat.disclaimer");
    }
  });

  it("공유 store의 쓰기 함수(acceptRevision·acceptPlanView·acceptNoPlan·clear·loadPlan)를 부르지 않는다: 대화창은 계획 상태를 읽기만 한다", () => {
    expect(sources.length).toBeGreaterThan(0);
    for (const { file, text } of all()) {
      expect(text, file).not.toMatch(/\.(acceptRevision|acceptPlanView|acceptNoPlan|clear|loadPlan)\s*\(/);
    }
  });
});

describe("AC-21 문구표 (copy/copy.ko.yml)", () => {
  it("대화창이 쓰는 키가 모두 있고, 대화창 초과 문장과 결과 화면 초과 안내는 별개 키다", () => {
    for (const key of [
      "disclaimer",
      "chat.alreadyExceeded",
      "chat.failed",
      "chat.pollingFailed",
      "chat.sendFailed",
      "validation.REQUIRED",
      "validation.OUT_OF_RANGE",
      "unit.char",
    ]) {
      expect(copy[key], key).toBeTruthy();
    }
    expect(copy["chat.alreadyExceeded"]).not.toBe(copy["result.alreadyExceeded"]);
    expect(copy["chat.disclaimer"]).toBeUndefined();
  });
});
