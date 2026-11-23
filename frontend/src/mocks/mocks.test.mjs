// 프론트 API 목 JSON과 계약 문서(docs/api/frontend-api.md)가 Issue #12 수용 기준을 지키는지 확인한다.
// 의존성 없이 `node --test frontend/src/mocks` 로 돌린다.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const docPath = path.resolve(here, "../../../docs/api/frontend-api.md");

const CHAT_FILES = [
  "post-messages.202.json",
  "post-messages.400.json",
  "post-messages.422-required.json",
  "post-messages.422-too-long.json",
  "post-messages.409-answer-pending.json",
  "post-messages.500.json",
  "get-messages.200-restore-empty.json",
  "get-messages.200-restore-history.json",
  "get-messages.200-restore-pending.json",
  "get-messages.seq-question-recalc.json",
  "get-messages.seq-explanation.json",
  "get-messages.200-failed.json",
  "get-messages.200-explanation-failed.json",
  "get-messages.200-clarify.json",
  "get-messages.200-out-of-scope.json",
  "get-messages.seq-500x3.json",
  "get-messages.401.json",
  "post-explanations.202.json",
  "post-explanations.404.json",
  "post-explanations.500.json",
  "get-comparison.200.json",
  "get-comparison.200-not-applicable.json",
  "get-comparison.200-etf0.json",
  "get-comparison.200-goal-changed.json",
  "get-comparison.200-asymmetric-exceeded.json",
  "get-comparison.200-changed-exceeded.json",
  "get-comparison.400.json",
  "get-comparison.404.json",
  "get-comparison.500.json",
  "post-apply.200.json",
  "post-apply.409-plan-changed.json",
  "post-apply.409-stale.json",
  "post-apply.409-no-plan.json",
  "post-apply.422-validation.json",
  "post-apply.422-field-not-changeable.json",
  "post-apply.404.json",
  "post-apply.500.json",
  "common.401-unauthenticated.json",
];

const AUTH_FILES = [
  "post-login.200.json",
  "post-login.401-invalid.json",
  "post-login.422.json",
  "post-login.500.json",
  "post-logout.200.json",
  "post-logout.401.json",
];

// 계약 C12a-12 표는 36행이다(본문의 "29개"는 422-custom-name 추가 전 숫자, P-6 6개는 #68). 표를 기준으로 한다.
const PLAN_FILES = [
  "get-plan.seq-full-flow.json",
  "get-plan.seq-exceeded-toggle.json",
  "get-plan.200.json",
  "get-plan.200-etf0.json",
  "get-plan.200-exceeded.json",
  "get-plan.200-after-apply.json",
  "get-plan.200-after-undo.json",
  "get-plan.200-ratio83.json",
  "get-plan.409-no-plan.json",
  "get-plan.401.json",
  "get-plan.500.json",
  "put-plan.seq-full-flow.json",
  "put-plan.200.json",
  "put-plan.200-exceeded.json",
  "put-plan.422-range.json",
  "put-plan.422-decimals.json",
  "put-plan.422-custom-name.json",
  "put-plan.409-plan-changed.json",
  "put-plan.400.json",
  "put-plan.500.json",
  "post-undo.200.json",
  "post-undo.409-nothing.json",
  "post-undo.409-plan-changed.json",
  "post-undo.409-no-plan.json",
  "post-undo.500.json",
  "post-plan-apply.200.json",
  "post-plan-apply.409-plan-changed.json",
  "post-plan-apply.409-no-plan.json",
  "post-plan-apply.422-validation.json",
  "post-plan-apply.422-field-not-changeable.json",
  "post-plan-apply.500.json",
];

// 기준 데이터(#220): ETF 종목 목록
const REFDATA_FILES = ["get-etf-products.200.json"];

// 대안(A-1, #66·#258): 기본은 순서 파일(첫 요청 = 기한 카드 빈 응답, 그다음 = 기한 48개월 응답)
const ALTERNATIVE_FILES = [
  "post-alternatives.seq.json",
  "post-alternatives.200.json",
  "post-alternatives.200-period.json",
  "post-alternatives.200-none.json",
  "post-alternatives.422-period.json",
  "post-alternatives.409-no-plan.json",
];

const DIRS = { chat: CHAT_FILES, auth: AUTH_FILES, plan: PLAN_FILES, refdata: REFDATA_FILES, alternative: ALTERNATIVE_FILES };

function load(dir, name) {
  return JSON.parse(fs.readFileSync(path.join(here, dir, name), "utf8"));
}

function bodies(dir, name) {
  return load(dir, name).responses.map((r) => r.body);
}

function body(dir, name, i = 0) {
  return bodies(dir, name)[i];
}

function allFiles() {
  return Object.entries(DIRS).flatMap(([dir, names]) => names.map((name) => ({ dir, name })));
}

// ---------------------------------------------------------------- 파일 목록

test("목 파일 개수: chat 38, auth 6, plan 30, refdata 1, alternative 6", () => {
  for (const [dir, names] of Object.entries(DIRS)) {
    const actual = fs.existsSync(path.join(here, dir))
      ? fs.readdirSync(path.join(here, dir)).filter((f) => f.endsWith(".json")).sort()
      : [];
    assert.deepEqual(actual, [...names].sort(), `${dir}/ 파일 목록이 계약 표와 다르다`);
  }
  assert.equal(CHAT_FILES.length, 38);
  assert.equal(AUTH_FILES.length + PLAN_FILES.length, 31);
  assert.equal(REFDATA_FILES.length, 1);
  assert.equal(ALTERNATIVE_FILES.length, 6);
});

test("ETF 종목 목록: 대표 상품(SP500)이 첫째이고 세 칸(code·displayName·gainTaxRatePct)만 있다(#220)", () => {
  const { products } = body("refdata", "get-etf-products.200.json");
  assert.equal(products[0].code, "SP500");
  for (const product of products) {
    assert.deepEqual(Object.keys(product).sort(), ["code", "displayName", "gainTaxRatePct"]);
    assert.equal(typeof product.gainTaxRatePct, "number");
    assert.match(product.displayName, / 추종 ETF$/);
  }
});

test("chat/get-messages.200-restore-pending.json 있음, post-explanations.seq-reuse.json 없음", () => {
  assert.ok(fs.existsSync(path.join(here, "chat/get-messages.200-restore-pending.json")));
  assert.ok(!fs.existsSync(path.join(here, "chat/post-explanations.seq-reuse.json")));
});

test("계약 문서의 목 표와 실제 파일 이름이 같다", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  const found = new Set();
  for (const m of doc.matchAll(/`((?:auth|plan|chat|refdata|alternative)\/)?([a-z0-9][a-z0-9.-]*\.json)`/g)) {
    const dir = m[1] ? m[1].slice(0, -1) : "chat";
    found.add(`${dir}/${m[2]}`);
  }
  const expected = new Set(allFiles().map(({ dir, name }) => `${dir}/${name}`));
  assert.deepEqual([...found].sort(), [...expected].sort());
});

// ---------------------------------------------------------------- 파일 형식

test("모든 파일이 {responses: [{status, headers, body}]} 형식이고 줄임표가 없다", () => {
  for (const { dir, name } of allFiles()) {
    const raw = fs.readFileSync(path.join(here, dir, name), "utf8");
    const file = JSON.parse(raw);
    assert.deepEqual(Object.keys(file), ["responses"], `${dir}/${name}`);
    assert.ok(Array.isArray(file.responses) && file.responses.length >= 1, `${dir}/${name}`);
    for (const r of file.responses) {
      assert.deepEqual(Object.keys(r), ["status", "headers", "body"], `${dir}/${name}`);
      assert.equal(typeof r.status, "number");
      const type = r.headers["Content-Type"];
      assert.equal(type, r.status >= 400 ? "application/problem+json" : "application/json", `${dir}/${name}`);
    }
    assert.ok(!raw.includes("..."), `${dir}/${name}에 줄임표`);
    assert.ok(!raw.includes("…"), `${dir}/${name}에 줄임표`);
  }
});

test("오류 응답은 C3-1 형식(type, title, status, code, errors[])이고 minDisplay·maxDisplay가 없다", () => {
  for (const { dir, name } of allFiles()) {
    for (const r of load(dir, name).responses.filter((x) => x.status >= 400)) {
      const b = r.body;
      assert.equal(b.type, "about:blank", `${dir}/${name}`);
      assert.equal(typeof b.title, "string");
      assert.equal(b.status, r.status, `${dir}/${name}: body.status`);
      assert.equal(typeof b.code, "string", `${dir}/${name}: code`);
      assert.ok(Array.isArray(b.errors), `${dir}/${name}: errors`);
      for (const e of b.errors) {
        assert.ok(!("minDisplay" in e) && !("maxDisplay" in e), `${dir}/${name}: minDisplay·maxDisplay`);
      }
    }
  }
});

test("401은 code=UNAUTHENTICATED, errors=[], problem+json이다", () => {
  const files = [
    ["chat", "common.401-unauthenticated.json"],
    ["chat", "get-messages.401.json"],
    ["auth", "post-logout.401.json"],
    ["plan", "get-plan.401.json"],
  ];
  for (const [dir, name] of files) {
    const [r] = load(dir, name).responses;
    assert.equal(r.status, 401);
    assert.equal(r.headers["Content-Type"], "application/problem+json");
    assert.equal(r.body.code, "UNAUTHENTICATED");
    assert.deepEqual(r.body.errors, []);
  }
  const [login] = load("auth", "post-login.401-invalid.json").responses;
  assert.equal(login.body.code, "INVALID_CREDENTIALS");
});

test("정상·422·401·409가 모두 있다", () => {
  const statuses = new Set();
  for (const { dir, name } of allFiles()) {
    for (const r of load(dir, name).responses) statuses.add(r.status);
  }
  for (const s of [200, 202, 400, 401, 404, 409, 422, 500]) assert.ok(statuses.has(s), `status ${s}`);
});

// ---------------------------------------------------------------- 오류 코드·상태

const ERROR_CODES = {
  "chat/post-messages.400.json": [400, "MALFORMED_REQUEST"],
  "chat/post-messages.422-required.json": [422, "VALIDATION_FAILED"],
  "chat/post-messages.422-too-long.json": [422, "VALIDATION_FAILED"],
  "chat/post-messages.409-answer-pending.json": [409, "ANSWER_PENDING"],
  "chat/post-messages.500.json": [500, "INTERNAL_ERROR"],
  "chat/post-explanations.404.json": [404, "NOT_FOUND"],
  "chat/post-explanations.500.json": [500, "INTERNAL_ERROR"],
  "chat/get-comparison.400.json": [400, "MALFORMED_REQUEST"],
  "chat/get-comparison.404.json": [404, "NOT_FOUND"],
  "chat/get-comparison.500.json": [500, "INTERNAL_ERROR"],
  "chat/post-apply.409-plan-changed.json": [409, "PLAN_CHANGED"],
  "chat/post-apply.409-stale.json": [409, "STALE_COMPARISON"],
  "chat/post-apply.409-no-plan.json": [409, "NO_PLAN"],
  "chat/post-apply.422-validation.json": [422, "VALIDATION_FAILED"],
  "chat/post-apply.422-field-not-changeable.json": [422, "FIELD_NOT_CHANGEABLE"],
  "chat/post-apply.404.json": [404, "NOT_FOUND"],
  "chat/post-apply.500.json": [500, "INTERNAL_ERROR"],
  "auth/post-login.401-invalid.json": [401, "INVALID_CREDENTIALS"],
  "auth/post-login.422.json": [422, "VALIDATION_FAILED"],
  "auth/post-login.500.json": [500, "INTERNAL_ERROR"],
  "plan/get-plan.409-no-plan.json": [409, "NO_PLAN"],
  "plan/get-plan.500.json": [500, "INTERNAL_ERROR"],
  "plan/put-plan.422-range.json": [422, "VALIDATION_FAILED"],
  "plan/put-plan.422-decimals.json": [422, "VALIDATION_FAILED"],
  "plan/put-plan.422-custom-name.json": [422, "VALIDATION_FAILED"],
  "plan/put-plan.409-plan-changed.json": [409, "PLAN_CHANGED"],
  "plan/put-plan.400.json": [400, "MALFORMED_REQUEST"],
  "plan/put-plan.500.json": [500, "INTERNAL_ERROR"],
  "plan/post-undo.409-nothing.json": [409, "NOTHING_TO_UNDO"],
  "plan/post-undo.409-plan-changed.json": [409, "PLAN_CHANGED"],
  "plan/post-undo.409-no-plan.json": [409, "NO_PLAN"],
  "plan/post-undo.500.json": [500, "INTERNAL_ERROR"],
  "plan/post-plan-apply.409-plan-changed.json": [409, "PLAN_CHANGED"],
  "plan/post-plan-apply.409-no-plan.json": [409, "NO_PLAN"],
  "plan/post-plan-apply.422-validation.json": [422, "VALIDATION_FAILED"],
  "plan/post-plan-apply.422-field-not-changeable.json": [422, "FIELD_NOT_CHANGEABLE"],
  "plan/post-plan-apply.500.json": [500, "INTERNAL_ERROR"],
};

test("오류 목의 HTTP 상태와 code가 계약 표와 같다", () => {
  for (const [file, [status, code]] of Object.entries(ERROR_CODES)) {
    const [dir, name] = file.split("/");
    const responses = load(dir, name).responses;
    assert.equal(responses.length, 1, file);
    assert.equal(responses[0].status, status, file);
    assert.equal(responses[0].body.code, code, file);
  }
});

test("422 예시의 errors[]: min·max·unit은 있고 minDisplay·maxDisplay는 없다", () => {
  assert.deepEqual(body("plan", "put-plan.422-range.json").errors, [
    { field: "monthlySavingAmount", reason: "OUT_OF_RANGE", min: 10000, max: 10000000, unit: "KRW" },
    { field: "targetPeriodMonths", reason: "REQUIRED" },
  ]);
  assert.deepEqual(body("plan", "put-plan.422-decimals.json").errors, [
    { field: "monthlySavingAmount", reason: "NOT_INTEGER" },
    { field: "savingsRateInputPct", reason: "TOO_MANY_DECIMALS", scale: 2 },
  ]);
  assert.deepEqual(body("plan", "put-plan.422-custom-name.json").errors, [
    { field: "goalCustomName", reason: "OUT_OF_RANGE", min: 1, max: 100, unit: "CHAR" },
  ]);
  assert.deepEqual(body("chat", "post-apply.422-validation.json").errors, [
    { field: "monthlySavingAmount", reason: "OUT_OF_RANGE", min: 10000, max: 10000000, unit: "KRW" },
  ]);
  assert.deepEqual(body("chat", "post-messages.422-required.json").errors, [{ field: "text", reason: "REQUIRED" }]);
  assert.deepEqual(body("chat", "post-messages.422-too-long.json").errors, [
    { field: "text", reason: "OUT_OF_RANGE", min: 1, max: 2000, unit: "CHAR" },
  ]);
  assert.deepEqual(body("auth", "post-login.422.json").errors, [{ field: "password", reason: "REQUIRED" }]);
});

test("FIELD_NOT_CHANGEABLE의 reason은 internal-calc C3-1을 따라 NOT_CHANGEABLE이다", () => {
  assert.deepEqual(body("chat", "post-apply.422-field-not-changeable.json").errors, [
    { field: "goalType", reason: "NOT_CHANGEABLE" },
  ]);
});

// ---------------------------------------------------------------- 인증

test("로그인·로그아웃 성공 본문은 {}이고 쿠키 설명 헤더가 있다", () => {
  const [login] = load("auth", "post-login.200.json").responses;
  assert.equal(login.status, 200);
  assert.deepEqual(login.body, {});
  assert.match(login.headers["Set-Cookie"], /^moeun_at=.+; Max-Age=1800; Path=\/; Secure; HttpOnly; SameSite=Lax$/);
  const [logout] = load("auth", "post-logout.200.json").responses;
  assert.deepEqual(logout.body, {});
  assert.match(logout.headers["Set-Cookie"], /^moeun_at=; Max-Age=0; Path=\/; Secure; HttpOnly; SameSite=Lax$/);
});

// ---------------------------------------------------------------- 계획(PlanView)

const A1_ASSUMPTIONS = {
  savingsRatePct: 3.12,
  isDefaultSavingsRate: true,
  savingsRateBaseMonth: "2026-10",
  savingsRatioPct: 50,
  taxRatePct: 15.4,
  scenarioMethod: "ROLLING_DISTRIBUTION",
  sp500BaseDate: "2026-10-31",
  excludedItems: ["ETF_TOTAL_EXPENSE", "EXCHANGE_RATE", "ETF_DISTRIBUTION", "HELD_ASSET_RETURN", "SAVINGS_REDEPOSIT"],
};

// 화면 전용 키 <S>.gapBadge(#224)는 plan-context에 없다. 공유 display만 글자까지 비교하려고 뺀다.
const withoutGapBadges = (display) => Object.fromEntries(Object.entries(display).filter(([key]) => !key.endsWith(".gapBadge")));
const gapBadgeKeys = (display) => Object.keys(display).filter((key) => key.endsWith(".gapBadge")).sort();

// plan-context 부록 A-1의 result·display (글자까지 같아야 한다)
const A1_SCENARIOS = [
  { scenario: "GOOD", afterTaxAsset: 18450000, principal: 15800000, afterTaxGain: 2650000, gapToGoal: -1550000, isAchieved: false, goalReachMonth: null },
  { scenario: "AVG", afterTaxAsset: 17120000, principal: 15800000, afterTaxGain: 1320000, gapToGoal: -2880000, isAchieved: false, goalReachMonth: null },
  { scenario: "BAD", afterTaxAsset: 15980000, principal: 15800000, afterTaxGain: 180000, gapToGoal: -4020000, isAchieved: false, goalReachMonth: null },
];

const A1_DISPLAY = {
  goalAmount: "약 2,000만원",
  calcStartMonth: "2026년 11월",
  targetMonth: "2029년 11월",
  "GOOD.afterTaxAsset": "약 1,845만원",
  "GOOD.principal": "약 1,580만원",
  "GOOD.afterTaxGain": "약 265만원",
  "GOOD.gapToGoal": "-약 155만원 미달",
  "AVG.afterTaxAsset": "약 1,712만원",
  "AVG.principal": "약 1,580만원",
  "AVG.afterTaxGain": "약 132만원",
  "AVG.gapToGoal": "-약 288만원 미달",
  "BAD.afterTaxAsset": "약 1,598만원",
  "BAD.principal": "약 1,580만원",
  "BAD.afterTaxGain": "약 18만원",
  "BAD.gapToGoal": "-약 402만원 미달",
  "conditions.heldAssets": "약 500만원",
  "conditions.monthlySavingAmount": "약 30만원",
  "conditions.targetPeriodMonths": "3년 0개월",
  "conditions.savingsRatioPct": "적금 50 : ETF 50",
  "conditions.savingsRateInputPct": "연 3.12%",
  "assumptions.savingsRateBaseMonth": "2026년 10월",
  "assumptions.sp500BaseDate": "2026년 10월",
  "assumptions.taxRatePct": "15.4%",
};

const PLAN_VIEW_KEYS = ["revision", "calcRunId", "baselineResultScenario", "undoAvailable", "plan", "result", "display"];
const PLAN_KEYS = [
  "goalType", "goalCustomName", "goalAmount", "targetPeriodMonths", "heldAssets",
  "monthlySavingAmount", "savingsRateInputPct", "savingsRatioPct",
];
const RESULT_KEYS = ["status", "goalAmount", "calcStartMonth", "targetMonth", "scenarios", "assumptions"];
const SCENARIO_KEYS = [
  "scenario", "afterTaxAsset", "principal", "afterTaxGain", "gapToGoal", "isAchieved", "goalReachMonth", "monthlyPoints",
];
const NOT_EXPORTED = [
  "conditions", "annualReturnPct", "requiredMonthlySaving", "dataVersions", "currentVersionId",
  "previousVersionId", "memberId", "baselineScenario", "calcStartMonth_plan",
];

const PLAN_VIEW_FILES = [
  ["get-plan.200.json", 0],
  ["get-plan.200-etf0.json", 0],
  ["get-plan.200-exceeded.json", 0],
  ["get-plan.200-after-apply.json", 0],
  ["get-plan.200-after-undo.json", 0],
  ["get-plan.seq-full-flow.json", 2],
  ["get-plan.seq-full-flow.json", 3],
  ["get-plan.seq-exceeded-toggle.json", 0],
  ["get-plan.seq-exceeded-toggle.json", 1],
  ["get-plan.seq-exceeded-toggle.json", 2],
  ["put-plan.200.json", 0],
  ["put-plan.200-exceeded.json", 0],
  ["put-plan.seq-full-flow.json", 0],
  ["put-plan.seq-full-flow.json", 1],
];

function addMonths(ym, k) {
  const [y, m] = ym.split("-").map(Number);
  const t = y * 12 + (m - 1) + k;
  return `${Math.floor(t / 12)}-${String((t % 12) + 1).padStart(2, "0")}`;
}

test("PlanView 모양: 정해진 키만 있고 내보내지 않는 키는 없다", () => {
  for (const [name, i] of PLAN_VIEW_FILES) {
    const v = body("plan", name, i);
    const where = `${name}[${i}]`;
    assert.deepEqual(Object.keys(v), PLAN_VIEW_KEYS, where);
    assert.deepEqual(Object.keys(v.plan), PLAN_KEYS, where);
    assert.deepEqual(Object.keys(v.result), RESULT_KEYS, where);
    for (const s of v.result.scenarios) assert.deepEqual(Object.keys(s), SCENARIO_KEYS, where);
    const text = JSON.stringify(v);
    for (const key of NOT_EXPORTED.filter((k) => !k.endsWith("_plan"))) {
      assert.ok(!text.includes(`"${key}"`), `${where}: ${key}`);
    }
    assert.ok(!("calcStartMonth" in v.plan), where);
    assert.ok(!text.includes("minDisplay"), where);
  }
});

// undoAvailable(D-095, #263): 첫 저장·되돌린 직후 false, 적용·입력 수정 저장 뒤 true
const UNDO_AVAILABLE = [
  ["get-plan.200.json", 0, false],
  ["get-plan.200-etf0.json", 0, false],
  ["get-plan.200-exceeded.json", 0, false],
  ["get-plan.200-after-apply.json", 0, true],
  ["get-plan.200-after-undo.json", 0, false],
  ["get-plan.seq-full-flow.json", 2, true],
  ["get-plan.seq-full-flow.json", 3, false],
  ["get-plan.seq-exceeded-toggle.json", 0, false],
  ["get-plan.seq-exceeded-toggle.json", 1, true],
  ["get-plan.seq-exceeded-toggle.json", 2, false],
  ["put-plan.200.json", 0, false],
  ["put-plan.200-exceeded.json", 0, false],
  ["put-plan.seq-full-flow.json", 0, false],
  ["put-plan.seq-full-flow.json", 1, true],
];

test("undoAvailable: 시나리오 흐름(첫 저장·적용·되돌리기·입력 수정)과 맞는다", () => {
  assert.equal(UNDO_AVAILABLE.length, PLAN_VIEW_FILES.length);
  for (const [name, i, expected] of UNDO_AVAILABLE) {
    assert.equal(body("plan", name, i).undoAvailable, expected, `${name}[${i}]`);
  }
});

test("monthlyPoints: targetPeriodMonths + 1점, month·afterTaxAsset만, 첫 점 = 보유 자산, 마지막 점 = 시나리오 값", () => {
  for (const [name, i] of PLAN_VIEW_FILES) {
    const v = body("plan", name, i);
    const where = `${name}[${i}]`;
    const n = v.plan.targetPeriodMonths;
    for (const s of v.result.scenarios) {
      assert.equal(s.monthlyPoints.length, n + 1, `${where} ${s.scenario}: 점 수`);
      s.monthlyPoints.forEach((p, k) => {
        assert.deepEqual(Object.keys(p), ["month", "afterTaxAsset"], where);
        assert.equal(p.month, addMonths(v.result.calcStartMonth, k), `${where} ${s.scenario} k=${k}`);
      });
      assert.equal(s.monthlyPoints[0].month, v.result.calcStartMonth);
      assert.equal(s.monthlyPoints[0].afterTaxAsset, v.plan.heldAssets);
      assert.equal(s.monthlyPoints[n].month, v.result.targetMonth);
      assert.equal(s.monthlyPoints[n].afterTaxAsset, s.afterTaxAsset);
    }
  }
});

test("get-plan.200: 기준 계획 A-1의 result·display가 plan-context 부록 A-1과 같고 37점이다", () => {
  const v = body("plan", "get-plan.200.json");
  assert.equal(v.revision, 12);
  assert.equal(v.calcRunId, 540);
  assert.equal(v.baselineResultScenario, "AVG");
  assert.deepEqual(v.plan, {
    goalType: "HOUSE",
    goalCustomName: null,
    goalAmount: 20000000,
    targetPeriodMonths: 36,
    heldAssets: 5000000,
    monthlySavingAmount: 300000,
    savingsRateInputPct: null,
    savingsRatioPct: null,
  });
  assert.equal(v.result.status, "CALCULATED");
  assert.equal(v.result.goalAmount, 20000000);
  assert.equal(v.result.calcStartMonth, "2026-11");
  assert.equal(v.result.targetMonth, "2029-11");
  assert.deepEqual(
    v.result.scenarios.map(({ monthlyPoints, ...rest }) => rest),
    A1_SCENARIOS,
  );
  for (const s of v.result.scenarios) assert.equal(s.monthlyPoints.length, 37);
  assert.deepEqual(v.result.assumptions, A1_ASSUMPTIONS);
  assert.deepEqual(withoutGapBadges(v.display), A1_DISPLAY);
  assert.deepEqual(gapBadgeKeys(v.display), ["AVG.gapBadge", "BAD.gapBadge", "GOOD.gapBadge"]);
});

test("monthlyPoints 공식: heldAssets + monthlySavingAmount × k + round(afterTaxGain × k / N)", () => {
  const v = body("plan", "get-plan.200.json");
  const avg = v.result.scenarios.find((s) => s.scenario === "AVG");
  avg.monthlyPoints.forEach((p, k) => {
    assert.equal(p.afterTaxAsset, 5000000 + 300000 * k + Math.floor((1320000 * k) / 36 + 0.5), `k=${k}`);
  });
});

test("put-plan.200 = get-plan.200, put-plan.200-exceeded = get-plan.200-exceeded", () => {
  assert.deepEqual(body("plan", "put-plan.200.json"), body("plan", "get-plan.200.json"));
  assert.deepEqual(body("plan", "put-plan.200-exceeded.json"), body("plan", "get-plan.200-exceeded.json"));
});

test("get-plan.200-etf0: A-2, 541, AVG 1개, savingsRatioPct 100", () => {
  const v = body("plan", "get-plan.200-etf0.json");
  assert.equal(v.calcRunId, 541);
  assert.equal(v.baselineResultScenario, "AVG");
  assert.equal(v.plan.savingsRatioPct, 100);
  assert.deepEqual(
    v.result.scenarios.map(({ monthlyPoints, ...rest }) => rest),
    [{ scenario: "AVG", afterTaxAsset: 15954412, principal: 15800000, afterTaxGain: 154412, gapToGoal: -4045588, isAchieved: false, goalReachMonth: null }],
  );
  assert.deepEqual(v.result.assumptions, {
    ...A1_ASSUMPTIONS, savingsRatioPct: 100, scenarioMethod: null, sp500BaseDate: null,
  });
  assert.deepEqual(gapBadgeKeys(v.display), ["AVG.gapBadge"]);
  assert.deepEqual(withoutGapBadges(v.display), {
    goalAmount: "약 2,000만원",
    calcStartMonth: "2026년 11월",
    targetMonth: "2029년 11월",
    "AVG.afterTaxAsset": "약 1,595만원",
    "AVG.principal": "약 1,580만원",
    "AVG.afterTaxGain": "약 15만원",
    "AVG.gapToGoal": "-약 405만원 미달",
    "conditions.heldAssets": "약 500만원",
    "conditions.monthlySavingAmount": "약 30만원",
    "conditions.targetPeriodMonths": "3년 0개월",
    "conditions.savingsRatioPct": "적금 100 : ETF 0",
    "conditions.savingsRateInputPct": "연 3.12%",
    "assumptions.savingsRateBaseMonth": "2026년 10월",
    "assumptions.taxRatePct": "15.4%",
  });
});

test("get-plan.200-exceeded: A-3, 542, ALREADY_EXCEEDED, scenarios 빈 배열, baselineResultScenario null", () => {
  const v = body("plan", "get-plan.200-exceeded.json");
  assert.equal(v.calcRunId, 542);
  assert.equal(v.baselineResultScenario, null);
  assert.equal(v.plan.heldAssets, 25000000);
  assert.equal(v.result.status, "ALREADY_EXCEEDED");
  assert.deepEqual(v.result.scenarios, []);
  assert.deepEqual(v.result.assumptions, A1_ASSUMPTIONS);
  assert.deepEqual(v.display, {
    goalAmount: "약 2,000만원",
    calcStartMonth: "2026년 11월",
    targetMonth: "2029년 11월",
    "conditions.heldAssets": "약 2,500만원",
    "conditions.monthlySavingAmount": "약 30만원",
    "conditions.targetPeriodMonths": "3년 0개월",
    "conditions.savingsRatioPct": "적금 50 : ETF 50",
    "conditions.savingsRateInputPct": "연 3.12%",
    "assumptions.savingsRateBaseMonth": "2026년 10월",
    "assumptions.sp500BaseDate": "2026년 10월",
    "assumptions.taxRatePct": "15.4%",
  });
});

test("get-plan.200-after-apply: rev 13 / 553, 월 50만원, 3구간 모두 달성, goalReachMonth 표시 키", () => {
  const v = body("plan", "get-plan.200-after-apply.json");
  assert.equal(v.revision, 13);
  assert.equal(v.calcRunId, 553);
  assert.equal(v.plan.monthlySavingAmount, 500000);
  assert.deepEqual(
    v.result.scenarios.map(({ monthlyPoints, ...rest }) => rest),
    [
      { scenario: "GOOD", afterTaxAsset: 27020000, principal: 23000000, afterTaxGain: 4020000, gapToGoal: 7020000, isAchieved: true, goalReachMonth: "2028-12" },
      { scenario: "AVG", afterTaxAsset: 25010000, principal: 23000000, afterTaxGain: 2010000, gapToGoal: 5010000, isAchieved: true, goalReachMonth: "2029-02" },
      { scenario: "BAD", afterTaxAsset: 23240000, principal: 23000000, afterTaxGain: 240000, gapToGoal: 3240000, isAchieved: true, goalReachMonth: "2029-04" },
    ],
  );
  assert.equal(v.display["AVG.afterTaxAsset"], "약 2,501만원");
  assert.equal(v.display["conditions.monthlySavingAmount"], "약 50만원");
  for (const s of ["GOOD", "AVG", "BAD"]) assert.ok(`${s}.goalReachMonth` in v.display, s);
});

test("get-plan.200-after-undo: rev 14 / 554, A-1 숫자", () => {
  const v = body("plan", "get-plan.200-after-undo.json");
  const base = body("plan", "get-plan.200.json");
  assert.equal(v.revision, 14);
  assert.equal(v.calcRunId, 554);
  assert.deepEqual({ ...v, revision: 12, calcRunId: 540 }, base);
});

test("goalReachMonth은 isAchieved일 때만 display에 있다", () => {
  for (const [name, i] of PLAN_VIEW_FILES) {
    const v = body("plan", name, i);
    for (const s of v.result.scenarios) {
      assert.equal(`${s.scenario}.goalReachMonth` in v.display, s.isAchieved, `${name}[${i}] ${s.scenario}`);
      assert.equal(s.goalReachMonth !== null, s.isAchieved, `${name}[${i}] ${s.scenario}`);
    }
  }
});

// ---------------------------------------------------------------- 시퀀스

test("get-plan.seq-full-flow: 401 -> 409 -> 200(rev 13/553) -> 200(rev 14/554)", () => {
  const rs = load("plan", "get-plan.seq-full-flow.json").responses;
  assert.deepEqual(rs.map((r) => r.status), [401, 409, 200, 200]);
  assert.equal(rs[0].body.code, "UNAUTHENTICATED");
  assert.equal(rs[1].body.code, "NO_PLAN");
  assert.deepEqual([rs[2].body.revision, rs[2].body.calcRunId], [13, 553]);
  assert.deepEqual([rs[3].body.revision, rs[3].body.calcRunId], [14, 554]);
});

test("put-plan.seq-full-flow: 200(rev 12/540) -> 200(rev 15/555, 48개월, 49점)", () => {
  const rs = load("plan", "put-plan.seq-full-flow.json").responses;
  assert.deepEqual(rs.map((r) => r.status), [200, 200]);
  assert.deepEqual(rs[0].body, body("plan", "get-plan.200.json"));
  const second = rs[1].body;
  assert.deepEqual([second.revision, second.calcRunId], [15, 555]);
  assert.equal(second.plan.targetPeriodMonths, 48);
  assert.equal(second.result.targetMonth, "2030-11");
  for (const s of second.result.scenarios) assert.equal(s.monthlyPoints.length, 49);
});

test("get-plan.seq-exceeded-toggle: 미달 rev 12/540 -> 초과 rev 13/542 -> 풀림 rev 14/554", () => {
  const rs = load("plan", "get-plan.seq-exceeded-toggle.json").responses;
  assert.deepEqual(rs.map((r) => r.status), [200, 200, 200]);
  assert.deepEqual(rs.map((r) => r.body.revision), [12, 13, 14]);
  assert.deepEqual(rs.map((r) => r.body.calcRunId), [540, 542, 554]);
  assert.deepEqual(rs.map((r) => r.body.result.status), ["CALCULATED", "ALREADY_EXCEEDED", "CALCULATED"]);
});

test("post-undo.200은 {revision: 14}", () => {
  assert.deepEqual(body("plan", "post-undo.200.json"), { revision: 14 });
});

// ---------------------------------------------------------------- 대화

const MESSAGE_KEYS = ["id", "role", "messageType", "status", "content", "citations", "createdAt"];
const MESSAGE_TYPES = ["QUESTION", "ANSWER", "EXPLANATION", "RECALC_RESULT", "CLARIFY", "OUT_OF_SCOPE", "FAILURE", null];

const POLL_FILES = [
  "get-messages.200-restore-empty.json",
  "get-messages.200-restore-history.json",
  "get-messages.200-restore-pending.json",
  "get-messages.seq-question-recalc.json",
  "get-messages.seq-explanation.json",
  "get-messages.200-failed.json",
  "get-messages.200-explanation-failed.json",
  "get-messages.200-clarify.json",
  "get-messages.200-out-of-scope.json",
];

test("F-2 메시지 모양: 키, 열거값, PENDING은 messageType·content가 null", () => {
  for (const name of POLL_FILES) {
    for (const b of bodies("chat", name)) {
      assert.deepEqual(Object.keys(b), ["messages", "hasPending", "answerPending"], name);
      let prevId = -1;
      for (const m of b.messages) {
        assert.deepEqual(Object.keys(m), MESSAGE_KEYS, name);
        assert.ok(m.id > prevId, `${name}: id 오름차순`);
        prevId = m.id;
        assert.ok(["USER", "ASSISTANT"].includes(m.role));
        assert.ok(MESSAGE_TYPES.includes(m.messageType), `${name}: ${m.messageType}`);
        assert.ok(["PENDING", "COMPLETED", "FAILED"].includes(m.status));
        assert.match(m.createdAt, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+09:00$/);
        if (m.status === "PENDING") {
          assert.equal(m.messageType, null, name);
          assert.equal(m.content, null, name);
        } else {
          assert.equal(typeof m.content, "string", name);
        }
        if (m.status === "FAILED") assert.equal(m.messageType, "FAILURE", name);
        if (m.role === "USER") assert.equal(m.status, "COMPLETED", name);
        for (const c of m.citations) assert.deepEqual(Object.keys(c), ["title", "sourceUrl", "orgName"], name);
        assert.ok(!("conditionChanges" in m) && !("calcRunId" in m), name);
      }
      assert.equal(b.hasPending, b.messages.some((m) => m.status === "PENDING"), `${name}: hasPending`);
    }
  }
});

test("get-messages.200-restore-empty는 빈 목록", () => {
  assert.deepEqual(body("chat", "get-messages.200-restore-empty.json"), {
    messages: [], hasPending: false, answerPending: false,
  });
});

test("get-messages.200-restore-pending: USER + ASSISTANT PENDING, hasPending·answerPending true", () => {
  const b = body("chat", "get-messages.200-restore-pending.json");
  assert.deepEqual(b.messages.map((m) => [m.id, m.role, m.status]), [[1030, "USER", "COMPLETED"], [1031, "ASSISTANT", "PENDING"]]);
  assert.equal(b.hasPending, true);
  assert.equal(b.answerPending, true);
});

test("get-messages.200-restore-history: 과거 RECALC_RESULT 2개 이상 + EXPLANATION 1개", () => {
  const { messages } = body("chat", "get-messages.200-restore-history.json");
  const recalc = messages.filter((m) => m.messageType === "RECALC_RESULT");
  assert.ok(recalc.length >= 2);
  assert.equal(messages.filter((m) => m.messageType === "EXPLANATION").length, 1);
  assert.ok(recalc.some((m) => m.id === 1027));
  assert.equal(Math.max(...recalc.map((m) => m.id)), 1031);
});

test("get-messages.seq-question-recalc: PENDING -> RECALC_RESULT COMPLETED", () => {
  const [a, b] = bodies("chat", "get-messages.seq-question-recalc.json");
  assert.deepEqual([a.hasPending, a.answerPending], [true, true]);
  assert.deepEqual([b.hasPending, b.answerPending], [false, false]);
  const done = b.messages.find((m) => m.id === 1031);
  assert.equal(done.messageType, "RECALC_RESULT");
  assert.equal(done.status, "COMPLETED");
  assert.equal(
    done.content,
    "월 저축액을 약 50만원으로 바꾸면 평균일 때 약 2,501만원으로 목표를 달성해요. 지금보다 약 789만원 증가해요.",
  );
  assert.equal(done.createdAt, "2026-11-16T10:15:31+09:00");
});

test("get-messages.seq-explanation: 설명 PENDING -> EXPLANATION COMPLETED, 서로 다른 운용사 citations 2개", () => {
  const [a, b] = bodies("chat", "get-messages.seq-explanation.json");
  assert.deepEqual([a.hasPending, a.answerPending], [true, false]);
  assert.deepEqual([b.hasPending, b.answerPending], [false, false]);
  const done = b.messages.find((m) => m.messageType === "EXPLANATION");
  assert.equal(done.status, "COMPLETED");
  assert.equal(done.citations.length, 2);
  assert.notEqual(done.citations[0].orgName, done.citations[1].orgName);
});

test("get-messages.seq-500x3: 500 세 번", () => {
  assert.deepEqual(load("chat", "get-messages.seq-500x3.json").responses.map((r) => r.status), [500, 500, 500]);
});

test("실패·되묻기·범위 밖 메시지 유형", () => {
  const last = (name) => body("chat", name).messages.at(-1);
  assert.deepEqual([last("get-messages.200-failed.json").messageType, last("get-messages.200-failed.json").status], ["FAILURE", "FAILED"]);
  assert.deepEqual([last("get-messages.200-explanation-failed.json").messageType, last("get-messages.200-explanation-failed.json").status], ["FAILURE", "FAILED"]);
  assert.equal(last("get-messages.200-clarify.json").messageType, "CLARIFY");
  assert.equal(last("get-messages.200-out-of-scope.json").messageType, "OUT_OF_SCOPE");
});

test("F-1 202 / F-3 202 / F-5 200 본문", () => {
  assert.deepEqual(body("chat", "post-messages.202.json"), { userMessageId: 1030, assistantMessageId: 1031 });
  assert.equal(load("chat", "post-messages.202.json").responses[0].status, 202);
  assert.deepEqual(body("chat", "post-explanations.202.json"), {});
  assert.equal(load("chat", "post-explanations.202.json").responses[0].status, 202);
  assert.deepEqual(body("chat", "post-apply.200.json"), { revision: 13 });
});

const COMPARISON_KEYS = ["messageId", "applicable", "changedKeys", "conditions", "current", "changed", "diff", "display"];

test("F-4 목: 경로로 구분되는 messageId, 키 모양, internal-calc A-1에서 calcRunId를 뺀 값", () => {
  const ok = body("chat", "get-comparison.200.json");
  const stale = body("chat", "get-comparison.200-not-applicable.json");
  const etf0 = body("chat", "get-comparison.200-etf0.json");
  assert.deepEqual([ok.messageId, ok.applicable], [1031, true]);
  assert.deepEqual([stale.messageId, stale.applicable], [1027, false]);
  assert.deepEqual([etf0.messageId, etf0.applicable], [1033, true]);
  for (const b of [ok, stale, etf0]) {
    assert.deepEqual(Object.keys(b), COMPARISON_KEYS);
    assert.deepEqual(b.changedKeys, ["monthlySavingAmount"]);
    assert.ok(!("calcRunId" in b));
    for (const side of [b.current, b.changed]) {
      assert.ok(!JSON.stringify(side).includes("monthlyPoints"));
    }
  }
  const internal = JSON.parse(
    fs.readFileSync(path.resolve(here, "../../../docs/api/examples/internal-calc/success-response.json"), "utf8"),
  );
  const { calcRunId, ...rest } = internal;
  assert.deepEqual(ok, { messageId: 1031, applicable: true, changedKeys: ["monthlySavingAmount"], ...rest });
  assert.deepEqual({ ...stale, messageId: 1031, applicable: true }, ok);
});

test("F-4 etf0: 시나리오 AVG 1개, savingsRatioPct 100", () => {
  const b = body("chat", "get-comparison.200-etf0.json");
  for (const side of [b.current, b.changed]) {
    assert.deepEqual(side.scenarios.map((s) => s.scenario), ["AVG"]);
  }
  assert.equal(b.conditions.current.savingsRatioPct, 100);
  assert.equal(b.conditions.changed.savingsRatioPct, 100);
  assert.deepEqual(b.diff.byScenario.map((s) => s.scenario), ["AVG"]);
  assert.equal(b.display["conditions.current.savingsRatioPct"], "적금 100 : ETF 0");
});

// 최상위 `block:` 안(들여쓰기 2칸)의 `key: "값"`만 읽는다. 다른 블록의 같은 키는 보지 않는다.
function blockValue(yaml, block, key) {
  const lines = yaml.split("\n");
  const start = lines.findIndex((l) => l === `${block}:`);
  assert.notEqual(start, -1, `${block}: 블록이 없다`);
  for (let i = start + 1; i < lines.length && (lines[i] === "" || /^[ #]/.test(lines[i])); i++) {
    const m = lines[i].match(new RegExp(`^ {2}${key}: "(.+)"$`));
    if (m) return m[1];
  }
  assert.fail(`${block}.${key}가 없다`);
}

test("blockValue는 앞선 블록의 같은 키(undo.failed)가 있어도 요청한 블록만 읽는다", () => {
  const yaml = ['undo:', '  failed: "되돌리기 실패"', 'chat:', '  # 주석', '  failed: "답변 실패"', 'result:', '  failed: "결과 실패"', ''].join("\n");
  assert.equal(blockValue(yaml, "chat", "failed"), "답변 실패");
  assert.equal(blockValue(yaml, "undo", "failed"), "되돌리기 실패");
  assert.throws(() => blockValue('chat:\n  sendFailed: "x"\nundo:\n  failed: "y"\n', "chat", "failed"));
});

test("실패·범위 밖 문구는 문구표(copy/copy.ko.yml)와 같다", () => {
  const copy = fs.readFileSync(path.resolve(here, "../../../copy/copy.ko.yml"), "utf8");
  const failed = blockValue(copy, "chat", "failed");
  const outOfScope = copy.match(/^"outOfScope": "(.+)"$/m)[1];
  assert.equal(body("chat", "get-messages.200-failed.json").messages.at(-1).content, failed);
  assert.equal(body("chat", "get-messages.200-explanation-failed.json").messages.at(-1).content, failed);
  assert.equal(body("chat", "get-messages.200-out-of-scope.json").messages.at(-1).content, outOfScope);
});

test("1027 메시지 문장은 /1027 비교 카드(1031과 같은 본문)와 같은 숫자다", () => {
  const { messages } = body("chat", "get-messages.200-restore-history.json");
  const card = body("chat", "get-comparison.200-not-applicable.json");
  assert.equal(messages.find((m) => m.id === 1027).content.includes(card.display["changed.AVG.afterTaxAsset"]), true);
});

test("대화 목의 문장에 개별 주식 종목 추천 표현이 없다", () => {
  // CF-07: 개별 주식 종목 추천만 금지한다. ETF 추천 표현과 ETF 상품명은 허용한다
  const banned = /(종목|주식)\s*(을|를)?\s*(추천|권해)|하세요|해야 해요|사세요|가입하/;
  for (const name of POLL_FILES) {
    for (const b of bodies("chat", name)) {
      for (const m of b.messages) {
        if (m.role === "ASSISTANT" && m.content) assert.ok(!banned.test(m.content), `${name}: ${m.content}`);
      }
    }
  }
});

// ---------------------------------------------------------------- README

test("mocks/README.md: 시퀀스는 요청 순서로 소비, GET /api/plan은 loadPlan() 경유", () => {
  const readme = fs.readFileSync(path.join(here, "README.md"), "utf8");
  assert.match(readme, /시퀀스 파일은 요청 순서로 소비/);
  assert.match(readme, /GET \/api\/plan/);
  assert.match(readme, /loadPlan\(\)/);
  assert.match(readme, /한 번만 소비/);
  assert.match(readme, /useMock/);
});

// ---------------------------------------------------------------- 계약 문서

test("계약 문서: P-6 요청·응답·검사 순서·프론트 흐름(#68)", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  for (const needle of [
    "### C12a-9. P-6 대안·비교 적용 `POST /api/plan/apply` — Should, #68 확정",
    '{ "path": "COMPARE", "expectedRevision": 13, "conditionChanges": { "monthlySavingAmount": 1500000 } }',
    '{ "revision": 14 }',
    "#### C12a-9-1. 서버 검사 순서", "#### C12a-9-2. 프론트 흐름",
    "`COMPARE`(비교 영역) 또는 `ALTERNATIVE`(대안 카드)", "expectedBaseVersionId=null",
    "| 409 | `NO_PLAN` | P-4, P-5, P-6 |", "| 409 | `PLAN_CHANGED` | P-3, P-5, P-6 |",
    "| 422 | `FIELD_NOT_CHANGEABLE` | P-6 |", "`plan/post-plan-apply.200.json` (기본)",
  ]) {
    assert.ok(doc.includes(needle), `문서에 ${needle}이 없다`);
  }
  assert.ok(!doc.includes("경로 예약만"), "P-6 경로 예약 문구가 남았다");
  assert.deepEqual(body("plan", "post-plan-apply.200.json"), { revision: 14 });
});

test("대안 목(A-1): 카드 3장 순서, 빈 카드 모양, 카드 본문 display 키(D-096)", () => {
  const order = ["monthlySavingAmount", "targetPeriodMonths", "savingsRatioPct"];
  const rowKeys = (key) => [
    `conditions.current.${key}`, `conditions.changed.${key}`,
    "diff.AVG.afterTaxAssetDelta", "changed.AVG.afterTaxAsset", "changed.AVG.gapToGoal",
  ];
  const first = body("alternative", "post-alternatives.200.json");
  assert.equal(first.hasAlternatives, true);
  assert.deepEqual(first.alternatives.map((c) => c.key), order);
  // 기간을 주지 않으면 기한 카드는 입력 전의 빈 카드다(D-039)
  const deadline = first.alternatives[1];
  assert.deepEqual(deadline.changes, {});
  assert.deepEqual(deadline.display, {});
  assert.equal(deadline.changed, null);
  for (const card of [first.alternatives[0], first.alternatives[2]]) {
    assert.deepEqual(Object.keys(card.changes), [card.key]);
    for (const k of rowKeys(card.key)) assert.ok(card.display[k], `${card.key}: ${k}`);
  }
  const period = body("alternative", "post-alternatives.200-period.json");
  assert.equal(period.alternatives[1].changes.targetPeriodMonths, 48);
  for (const k of rowKeys("targetPeriodMonths")) assert.ok(period.alternatives[1].display[k], k);
  assert.deepEqual(bodies("alternative", "post-alternatives.seq.json"), [first, period]);
  assert.deepEqual(body("alternative", "post-alternatives.200-none.json"), { hasAlternatives: false, alternatives: [] });
  const invalid = body("alternative", "post-alternatives.422-period.json");
  assert.equal(invalid.code, "VALIDATION_FAILED");
  assert.deepEqual(invalid.errors[0], { field: "targetPeriodMonths", reason: "OUT_OF_RANGE", min: 1, max: 360, unit: "MONTH" });
  assert.equal(body("alternative", "post-alternatives.409-no-plan.json").code, "NO_PLAN");
});

test("계약 문서: 대안 절 A-1(#258, D-096)", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  for (const needle of [
    "| A-1 | `POST /api/alternatives` |", "## 5. 대안 절 (#66, #258)", "### A-1. `POST /api/alternatives`",
    '{ "targetPeriodMonths": 48 }', "`hasAlternatives`", "`diff.AVG.afterTaxAssetDelta`", "D-096",
    "`alternative/post-alternatives.seq.json` (기본)",
  ]) {
    assert.ok(doc.includes(needle), `문서에 ${needle}이 없다`);
  }
});

test("계약 문서: 엔드포인트·오류 코드 예시", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  for (const needle of [
    "POST /api/auth/login", "POST /api/auth/logout", "PUT /api/plan", "GET /api/plan", "POST /api/plan/undo",
    "POST /api/plan/apply", "POST /api/chat/messages", "GET /api/chat/messages?afterId=", "POST /api/chat/explanations",
    "/comparison", "/apply",
    "PLAN_CHANGED", "STALE_COMPARISON", "NOTHING_TO_UNDO", "ANSWER_PENDING", "NO_PLAN", "INVALID_CREDENTIALS",
    "FIELD_NOT_CHANGEABLE", "UNAUTHENTICATED", "VALIDATION_FAILED",
  ]) {
    assert.ok(doc.includes(needle), `문서에 ${needle}이 없다`);
  }
});

test("계약 문서: 대화 절 모양(F-1~F-5)과 초과·고지 규칙", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  for (const needle of [
    '{ "userMessageId": 1030, "assistantMessageId": 1031 }',
    '"answerPending"', "hasPending", '"changedKeys"', '"applicable"', '"messageId"',
    '{ "expectedRevision": 12 }', '{ "revision": 13 }',
    "chat.alreadyExceeded", "`disclaimer`", "CLARIFY", "D-063", "D-064", "D-066",
  ]) {
    assert.ok(doc.includes(needle), `문서에 ${needle}이 없다`);
  }
});

test("계약 문서: F-3 오류 규칙과 호출 조건", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  assert.match(doc, /없는 `calcRunId`, 남의 기록, CHAT 기록은 모두 같은 `404 NOT_FOUND`/);
  assert.match(doc, /초과 계획\(`status=ALREADY_EXCEEDED`\)에서는 부르지 않는다(\*\*)?\(D-062\)/);
});

test("계약 문서: 모듈 경계 — plan -> chat 의존 없음, 의존 표, 대화 삭제 이벤트", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  assert.match(doc, /\| `chat` \| `\{calc, rag\}` \| \*\*`\{calc, rag, plan, auth\}`\*\*/);
  assert.match(doc, /\| `auth` \| `\{\}` \| \*\*`\{\}`\(변경 없음\)\*\*/);
  assert.match(doc, /\| `plan` \| `\{\}` \| `\{\}` \| 대화를 모른다/);
  assert.match(doc, /\*\*calc → chat, plan → \(chat·calc\), auth → \(어느 모듈이든\)\*\* 이 생기지 않는다/);
  assert.match(doc, /`auth\.MemberLoggedIn\{memberId\}`·`auth\.MemberLoggedOut\{memberId\}`/);
  assert.match(doc, /동기 `@EventListener`/);
  assert.ok(doc.includes("ModularityTests"));
  assert.match(doc, /\| `calc` \| `\{plan, refdata\}` \|/);
});

test("계약 문서: 인증·배포 전제와 쿠키 표", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  for (const needle of [
    "`SameSite=Lax` 이상 필수", "같은 사이트", "Vite 프록시", "credentials: \"include\"",
    "**F-2만 `@SkipRenewal`**", "사용자 조작 요청 중 연장 제외는 F-2뿐이다. 로그인·로그아웃은 핸들러가 쿠키를 직접 내리므로 `@SkipRenewal`을 붙인다.",
    "| 이름 | `moeun_at` |", "| `HttpOnly` | 항상 |", "| `Secure` | 기본 `true`. `auth.cookie.secure=false`는 `local` 프로필에서만 |",
    "| `SameSite` | `Lax` |", "| `Path` | `/` |", "| `Max-Age` | `1800`. 로그아웃·401 때 `0` |",
    "JWT 서명 키는 설정값(환경 변수)",
  ]) {
    assert.ok(doc.includes(needle), `문서에 ${needle}이 없다`);
  }
});

test("계약 문서: 401은 C3-1 형식(code=UNAUTHENTICATED, errors=[], problem+json)", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  assert.match(doc, /`401 UNAUTHENTICATED`, `errors: \[\]`, `application\/problem\+json`/);
});

test("계약 문서: 프론트 API 422 예시는 min·max·unit만 있고 minDisplay·maxDisplay가 없다", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  const blocks = [...doc.matchAll(/```json\n([\s\S]*?)```/g)].map((m) => m[1]).filter((b) => b.includes("VALIDATION_FAILED"));
  assert.ok(blocks.length >= 3);
  for (const b of blocks) assert.ok(!b.includes("minDisplay") && !b.includes("maxDisplay"));
  assert.ok(doc.includes("`minDisplay`·`maxDisplay`는 프론트 API에 없다"));
});

test("계약 문서: 계획·인증 절 — PlanView, monthlyPoints, goalType, 변환 규칙, 공유 상태, 문구표", () => {
  const doc = fs.readFileSync(docPath, "utf8");
  for (const needle of [
    "`revision`", "`calcRunId`", "`baselineResultScenario`", "`display`",
    "점 수 = `targetPeriodMonths + 1`", "`principal`은 내보내지 않는다", "화면 View(#30·#31)는 엔진 목록을 복사만 하고",
    "HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND", "CUSTOM",
    "`min:1, max:100, unit:\"CHAR\"`", "| `goalCustomName` | 1 | 100 | CHAR |",
    "(d) 만원 → 원", "(e) 기간", "(f) 422 범위 문구",
    "`settledStatus`", "`acceptNoPlan(sentRevision)`", "single-flight", "`loadPlan()`",
    "C12a-7-1. 문구표 키 전체 목록", "`result.alreadyExceeded`", "`common.networkError`", "`undo.nothingToUndo`",
    "P-6", "#68", "계약 완료",
  ]) {
    assert.ok(doc.includes(needle), `문서에 ${needle}이 없다`);
  }
  assert.match(doc, /\| `plan` \| `\{\}` \| P-5·P-6 컨트롤러는 `plan\.web` \|/);
});
