// 목 모드 전송 계층. frontend/src/mocks/README.md의 "선택 방식"을 구현한다.
// 개발·테스트 전용이다. 화면 코드는 부르지 않는다(래퍼가 mode: "mock"일 때만 부른다).

interface MockResponse {
  status: number;
  headers: Record<string, string>;
  body: unknown;
}

interface MockFile {
  responses: MockResponse[];
}

const modules = import.meta.glob<MockFile>("../mocks/**/*.json", { eager: true, import: "default" });

const files = new Map<string, MockResponse[]>();
for (const [path, file] of Object.entries(modules)) {
  files.set(path.slice(path.lastIndexOf("/") + 1), file.responses);
}

const COMMON_PREFIX = "common";

// 파일 이름 접두어 -> 요청 키(메서드 + 경로). 계약 문서 C12a-12·C12b-12 표와 같다.
// 경로의 messageId는 :id로 둔다. 고른 파일은 모든 messageId에 적용된다.
const KEY_BY_PREFIX: Record<string, string> = {
  "post-messages": "POST /api/chat/messages",
  "get-messages": "GET /api/chat/messages",
  "post-explanations": "POST /api/chat/explanations",
  "get-comparison": "GET /api/chat/messages/:id/comparison",
  "post-apply": "POST /api/chat/messages/:id/apply",
  "post-login": "POST /api/auth/login",
  "post-logout": "POST /api/auth/logout",
  "get-plan": "GET /api/plan",
  "put-plan": "PUT /api/plan",
  "post-undo": "POST /api/plan/undo",
};

// 요청 키마다 기본 파일 하나. F-4는 messageId별 기본 파일이 있고, 그 밖의 id는 404 목이다(가정 A-6).
const DEFAULT_FILE_BY_KEY: Record<string, string> = {
  "POST /api/chat/messages": "post-messages.202.json",
  "GET /api/chat/messages": "get-messages.200-restore-empty.json",
  "POST /api/chat/explanations": "post-explanations.202.json",
  "GET /api/chat/messages/1031/comparison": "get-comparison.200.json",
  "GET /api/chat/messages/1027/comparison": "get-comparison.200-not-applicable.json",
  "GET /api/chat/messages/1033/comparison": "get-comparison.200-etf0.json",
  "GET /api/chat/messages/:id/comparison": "get-comparison.404.json",
  "POST /api/chat/messages/:id/apply": "post-apply.200.json",
  "POST /api/auth/login": "post-login.200.json",
  "POST /api/auth/logout": "post-logout.200.json",
  "GET /api/plan": "get-plan.seq-full-flow.json",
  "PUT /api/plan": "put-plan.seq-full-flow.json",
  "POST /api/plan/undo": "post-undo.200.json",
};

const MESSAGE_ID_PATHS: Array<[RegExp, string]> = [
  [/^\/api\/chat\/messages\/[^/]+\/comparison$/, "/api/chat/messages/:id/comparison"],
  [/^\/api\/chat\/messages\/[^/]+\/apply$/, "/api/chat/messages/:id/apply"],
];

let selected = new Map<string, string>(); // 요청 키 -> useMock으로 고른 파일 이름
let commonFile: string | null = null; // 모든 키에 적용되는 공통 파일
let calls = new Map<string, number>(); // 호출 횟수 키 -> 지금까지 부른 횟수

function responsesOf(fileName: string): MockResponse[] {
  const responses = files.get(fileName);
  if (!responses) throw new Error(`목 파일이 없다: ${fileName}`);
  return responses;
}

/** 목 파일 하나를 골라 그 요청 키(또는 공통 파일이면 모든 키)의 응답으로 쓴다. 그 키의 호출 횟수는 0으로 돌아간다. */
export function useMock(fileName: string): void {
  selectMockFile(fileName);
}

/** useMock의 알맹이. 이름이 use로 시작하지 않아 훅 규칙 검사에 걸리지 않는다(화면 코드가 아니다). */
function selectMockFile(fileName: string): void {
  responsesOf(fileName);
  const prefix = fileName.split(".")[0];
  if (prefix === COMMON_PREFIX) {
    selected = new Map();
    commonFile = fileName;
    calls = new Map();
    return;
  }
  const key = KEY_BY_PREFIX[prefix];
  if (!key) throw new Error(`요청 키를 알 수 없는 목 파일이다: ${fileName}`);
  selected.set(key, fileName);
  calls.delete(key);
}

/** 선택과 호출 횟수를 모두 되돌린다. 테스트 격리용. */
export function resetMocks(): void {
  selected = new Map();
  commonFile = null;
  calls = new Map();
}

function toKeys(method: string, path: string): { concrete: string; generic: string } {
  const concrete = `${method} ${path}`;
  for (const [pattern, generic] of MESSAGE_ID_PATHS) {
    if (pattern.test(path)) return { concrete, generic: `${method} ${generic}` };
  }
  return { concrete, generic: concrete };
}

function pathOf(input: string): string {
  const end = input.search(/[?#]/);
  return end === -1 ? input : input.slice(0, end);
}

/**
 * 개발 중 기본 목을 바꿔 보는 길(계약 C12a-12·C12b-12의 기본 파일은 그대로 둔다).
 * `VITE_MOCK_FILES=get-plan.200.json,get-messages.200-failed.json npm run dev`처럼 쉼표로 적는다.
 * 예: 대안 영역은 미달일 때만 보이므로(FR-ALT-01) `get-plan.200.json`을 골라야 보인다.
 */
export function applyMockFilesFromEnv(env: Record<string, unknown>): string[] {
  const raw = env.VITE_MOCK_FILES;
  if (typeof raw !== "string" || raw.trim() === "") return [];
  const names = raw
    .split(",")
    .map((name) => name.trim())
    .filter((name) => name !== "");
  for (const name of names) selectMockFile(name);
  return names;
}

/** 실제 fetch처럼 요청 키(메서드 + 경로, 쿼리 제외)에 맞는 목 응답을 Response로 돌려준다. */
export async function mockFetch(input: string, init?: { method?: string }): Promise<Response> {
  const method = (init?.method ?? "GET").toUpperCase();
  const { concrete, generic } = toKeys(method, pathOf(input));

  const chosen = selected.get(generic) ?? commonFile;
  const fileName = chosen ?? DEFAULT_FILE_BY_KEY[concrete] ?? DEFAULT_FILE_BY_KEY[generic];
  if (!fileName) throw new Error(`목이 없는 요청이다: ${concrete}`);

  // 같은 틱의 동시 호출도 순서대로 하나씩 소비한다.
  const counterKey = chosen ? generic : concrete;
  const count = calls.get(counterKey) ?? 0;
  calls.set(counterKey, count + 1);

  const responses = responsesOf(fileName);
  const { status, headers, body } = responses[Math.min(count, responses.length - 1)];
  // Set-Cookie는 브라우저가 Response에 넣지 않는 금지 헤더라 뺀다(설명용).
  const allowed = Object.entries(headers).filter(([name]) => name.toLowerCase() !== "set-cookie");
  return new Response(JSON.stringify(body), { status, headers: Object.fromEntries(allowed) });
}
