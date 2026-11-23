// AC-2, AC-4, AC-5, AC-6(상태·헤더), AC-7, AC-11: 목 선택 방식(C12b-12, C12a-12).
import { beforeEach, describe, expect, it } from "vitest";
import { mockFetch, resetMocks, useMock } from "../mock";
import { allMockFiles, readMock } from "./helpers";

type Key = [method: string, path: string];

// 파일 이름 접두어 -> 요청 키. 계약 문서 C12b-12·C12a-12 표와 같다.
// F-4·F-5는 경로에 messageId가 들어간다. 고른 파일은 모든 messageId에 적용된다(spec.md 가정 A-6).
const KEY_BY_PREFIX: Record<string, Key> = {
  "post-messages": ["POST", "/api/chat/messages"],
  "get-messages": ["GET", "/api/chat/messages"],
  "post-explanations": ["POST", "/api/chat/explanations"],
  "get-comparison": ["GET", "/api/chat/messages/1031/comparison"],
  "post-apply": ["POST", "/api/chat/messages/1031/apply"],
  "post-login": ["POST", "/api/auth/login"],
  "post-logout": ["POST", "/api/auth/logout"],
  "get-plan": ["GET", "/api/plan"],
  "put-plan": ["PUT", "/api/plan"],
  "post-undo": ["POST", "/api/plan/undo"],
  "post-plan-apply": ["POST", "/api/plan/apply"],
  "post-alternatives": ["POST", "/api/alternatives"],
};

const prefixOf = (file: string) => file.split(".")[0];
const call = (key: Key) => mockFetch(key[1], { method: key[0] });

// 요청 키마다 기본 파일 하나(계약 표의 "(기본)" 표시).
const DEFAULTS: Array<[Key, string]> = [
  [["POST", "/api/chat/messages"], "post-messages.202.json"],
  [["GET", "/api/chat/messages"], "get-messages.200-restore-empty.json"],
  [["POST", "/api/chat/explanations"], "post-explanations.202.json"],
  [["GET", "/api/chat/messages/1031/comparison"], "get-comparison.200.json"],
  [["GET", "/api/chat/messages/1027/comparison"], "get-comparison.200-not-applicable.json"],
  [["GET", "/api/chat/messages/1033/comparison"], "get-comparison.200-etf0.json"],
  [["POST", "/api/chat/messages/1031/apply"], "post-apply.200.json"],
  [["POST", "/api/auth/login"], "post-login.200.json"],
  [["POST", "/api/auth/logout"], "post-logout.200.json"],
  [["GET", "/api/plan"], "get-plan.seq-full-flow.json"],
  [["PUT", "/api/plan"], "put-plan.seq-full-flow.json"],
  [["POST", "/api/plan/undo"], "post-undo.200.json"],
  [["POST", "/api/plan/apply"], "post-plan-apply.200.json"],
  [["POST", "/api/alternatives"], "post-alternatives.seq.json"],
];

beforeEach(() => resetMocks());

describe("AC-2·AC-11 기본 파일: 키마다 기본 파일이 하나 있고 그 JSON을 돌려준다", () => {
  it.each(DEFAULTS)("%j 의 첫 응답은 %s 의 첫 응답이다", async (key, file) => {
    const expected = readMock(file).responses[0];
    const res = await call(key);
    expect(res.status).toBe(expected.status);
    expect(res.headers.get("content-type")).toBe(expected.headers["Content-Type"]);
    expect(await res.json()).toEqual(expected.body);
  });

  it("get-plan·put-plan 기본은 시퀀스 파일이다(401 -> 409 -> 200 -> 200 / 200 -> 200)", async () => {
    const getStatuses: number[] = [];
    for (let i = 0; i < 4; i++) getStatuses.push((await call(["GET", "/api/plan"])).status);
    expect(getStatuses).toEqual([401, 409, 200, 200]);

    const putRevisions: number[] = [];
    for (let i = 0; i < 2; i++) putRevisions.push((await (await call(["PUT", "/api/plan"])).json()).revision);
    expect(putRevisions).toEqual([12, 15]);
  });
});

describe("AC-5·AC-11 useMock: 같은 키의 다른 파일을 고른다 (파일 81개 전부)", () => {
  const files = allMockFiles().filter((f) => prefixOf(f) !== "common");

  it("목 파일은 chat 38 + auth 6 + plan 31 + alternative 6 = 81개이고, 공통 파일 1개를 뺀 나머지가 위 접두어 표에 모두 속한다", () => {
    expect(allMockFiles()).toHaveLength(81);
    expect(files.every((f) => prefixOf(f) in KEY_BY_PREFIX)).toBe(true);
  });

  it.each(files)("useMock(%s) 뒤 첫 호출은 그 파일의 첫 응답(상태·Content-Type·본문)이다", async (file) => {
    const expected = readMock(file).responses[0];
    useMock(file);
    const res = await call(KEY_BY_PREFIX[prefixOf(file)]);
    expect(res.status).toBe(expected.status);
    expect(res.headers.get("content-type")).toBe(expected.headers["Content-Type"]);
    expect(await res.json()).toEqual(expected.body);
  });

  it("공통 파일 common.401-unauthenticated.json 은 모든 요청 키에 적용된다", async () => {
    useMock("common.401-unauthenticated.json");
    for (const key of [["GET", "/api/plan"], ["POST", "/api/chat/messages"], ["PUT", "/api/plan"]] as Key[]) {
      const res = await call(key);
      expect(res.status).toBe(401);
      expect((await res.json()).code).toBe("UNAUTHENTICATED");
    }
  });

  it("없는 파일 이름이면 즉시 오류를 던진다(오타 방지)", () => {
    expect(() => useMock("nope.json")).toThrow();
  });

  it("useMock으로 고르는 것은 그 파일의 키만 바꾸고 다른 키는 기본 파일 그대로다", async () => {
    useMock("post-login.401-invalid.json");
    expect((await call(["POST", "/api/auth/login"])).status).toBe(401);
    expect((await call(["POST", "/api/auth/logout"])).status).toBe(200);
  });

  it("resetMocks()는 선택과 호출 횟수를 모두 되돌린다", async () => {
    useMock("get-messages.200-clarify.json");
    await call(["GET", "/api/chat/messages"]);
    resetMocks();
    const body = await (await call(["GET", "/api/chat/messages"])).json();
    expect(body).toEqual(readMock("get-messages.200-restore-empty.json").responses[0].body);
  });
});

describe("AC-4 시퀀스: i번째 호출에 i번째 응답, 끝을 넘으면 마지막 응답 반복", () => {
  it("get-messages.seq-question-recalc(2개): 1번 -> 2번 -> 2번 반복", async () => {
    const file = readMock("get-messages.seq-question-recalc.json").responses;
    expect(file).toHaveLength(2);
    useMock("get-messages.seq-question-recalc.json");
    for (const expected of [file[0], file[1], file[1], file[1]]) {
      expect(await (await call(["GET", "/api/chat/messages"])).json()).toEqual(expected.body);
    }
  });

  it("get-messages.seq-500x3: 500이 3번, 4번째 이후도 500", async () => {
    useMock("get-messages.seq-500x3.json");
    for (let i = 0; i < 5; i++) expect((await call(["GET", "/api/chat/messages"])).status).toBe(500);
  });

  it("길이 1 배열이면 같은 응답이 계속 반복된다", async () => {
    const expected = readMock("post-messages.202.json").responses[0].body;
    for (let i = 0; i < 3; i++) {
      expect(await (await call(["POST", "/api/chat/messages"])).json()).toEqual(expected);
    }
  });

  it("요청 키는 메서드 + 경로다: 쿼리는 제외하고 같은 키로 센다", async () => {
    const file = readMock("get-messages.seq-question-recalc.json").responses;
    useMock("get-messages.seq-question-recalc.json");
    const first = await (await mockFetch("/api/chat/messages?afterId=0", { method: "GET" })).json();
    const second = await (await mockFetch("/api/chat/messages?afterId=1031", { method: "GET" })).json();
    expect(first).toEqual(file[0].body);
    expect(second).toEqual(file[1].body);
  });

  it("키마다 호출 횟수를 따로 센다: 다른 키 호출이 시퀀스를 밀지 않는다", async () => {
    await call(["GET", "/api/chat/messages"]);
    await call(["POST", "/api/chat/messages"]);
    await call(["GET", "/api/plan"]); // 1번 호출
    expect((await call(["GET", "/api/plan"])).status).toBe(409); // 2번
  });

  it("메서드가 다르면 다른 키다: GET /api/plan 과 PUT /api/plan 은 따로 센다", async () => {
    await call(["PUT", "/api/plan"]);
    expect((await call(["GET", "/api/plan"])).status).toBe(401); // GET은 아직 1번째
  });

  it("useMock으로 파일을 고르면 그 키의 호출 횟수가 0으로 돌아가고, 같은 파일을 다시 골라도 처음부터다", async () => {
    await call(["GET", "/api/plan"]);
    await call(["GET", "/api/plan"]);
    useMock("get-plan.seq-exceeded-toggle.json");
    const revisions: number[] = [];
    for (let i = 0; i < 4; i++) revisions.push((await (await call(["GET", "/api/plan"])).json()).revision);
    expect(revisions).toEqual([12, 13, 14, 14]);
    useMock("get-plan.seq-exceeded-toggle.json");
    expect((await (await call(["GET", "/api/plan"])).json()).revision).toBe(12);
  });
});

describe("AC-6 mockFetch는 실제 fetch처럼 status·headers를 그대로 반영한 Response를 돌려준다", () => {
  it.each([
    ["post-messages.500.json", ["POST", "/api/chat/messages"] as Key, 500, "INTERNAL_ERROR"],
    ["post-login.401-invalid.json", ["POST", "/api/auth/login"] as Key, 401, "INVALID_CREDENTIALS"],
    ["put-plan.409-plan-changed.json", ["PUT", "/api/plan"] as Key, 409, "PLAN_CHANGED"],
    ["put-plan.422-range.json", ["PUT", "/api/plan"] as Key, 422, undefined],
  ])("%s", async (file, key, status, code) => {
    useMock(file);
    const res = await call(key);
    expect(res).toBeInstanceOf(Response);
    expect(res.status).toBe(status);
    expect(res.ok).toBe(false);
    expect(res.headers.get("content-type")).toBe("application/problem+json");
    const body = await res.json();
    if (code) expect(body.code).toBe(code);
  });

  it("2xx 목은 ok=true, Content-Type application/json", async () => {
    const res = await call(["POST", "/api/plan/undo"]);
    expect(res.ok).toBe(true);
    expect(res.headers.get("content-type")).toBe("application/json");
  });

  it("목이 없는 요청 키는 어떤 키인지 적힌 오류로 거절한다", async () => {
    await expect(mockFetch("/api/unknown", { method: "GET" })).rejects.toThrow(/GET \/api\/unknown/);
  });
});

describe("AC-7 F-4 목은 경로 키만으로 서로 다른 파일이 선택된다", () => {
  it("1031 정상 / 1027 적용 불가 / 1033 ETF 0%를 한꺼번에(한 화면에 카드 여러 개) 부를 수 있다", async () => {
    const [a, b, c] = await Promise.all(
      [1031, 1027, 1033].map(async (id) => (await mockFetch(`/api/chat/messages/${id}/comparison`)).json()),
    );
    expect(a).toEqual(readMock("get-comparison.200.json").responses[0].body);
    expect(a.messageId).toBe(1031);
    expect(a.applicable).toBe(true);
    expect(b).toEqual(readMock("get-comparison.200-not-applicable.json").responses[0].body);
    expect(b.messageId).toBe(1027);
    expect(b.applicable).toBe(false);
    expect(c).toEqual(readMock("get-comparison.200-etf0.json").responses[0].body);
    expect(c.messageId).toBe(1033);
  });

  it("목이 없는 messageId는 get-comparison.404.json 응답이다", async () => {
    const res = await mockFetch("/api/chat/messages/9999/comparison");
    expect(res.status).toBe(404);
    expect((await res.json()).code).toBe("NOT_FOUND");
  });

  it("F-4 오류 목은 useMock으로 고르고, 고른 파일은 모든 messageId에 적용된다", async () => {
    useMock("get-comparison.500.json");
    for (const id of [1031, 1027, 1033]) {
      expect((await mockFetch(`/api/chat/messages/${id}/comparison`)).status).toBe(500);
    }
  });

  it("F-5 apply도 messageId와 무관하게 같은 키다", async () => {
    const expected = readMock("post-apply.200.json").responses[0].body;
    for (const id of [1031, 1027]) {
      const res = await mockFetch(`/api/chat/messages/${id}/apply`, { method: "POST" });
      expect(await res.json()).toEqual(expected);
    }
  });
});
