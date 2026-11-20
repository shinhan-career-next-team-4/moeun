// @vitest-environment jsdom
// Issue #53 AC-2·AC-11~AC-13: 비교 카드 그리기(C12b-6, display 그대로, 문구표 키), F-4 오류, 적용 전 내 계획 불변.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { compile } from "@tailwindcss/node";
import { cleanup, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { jsonResponse, problemResponse } from "../../../../api/__tests__/helpers";
import { LOGIN_PATH } from "../../../../app/routes";
import { copy, doneMsg, flush, malformed, page, pendingMsg, problem, serverError, tick, userMsg } from "../../__tests__/helpers";
import {
  CONDITION_KEYS,
  buttonOf,
  cardOf,
  applyButtons,
  card,
  cards,
  cellText,
  comparison,
  conditionRows,
  deferred,
  failedNotice,
  makeApi,
  makeLiveEnv,
  makeStore,
  mountChat,
  scenarioRows,
} from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요");

/**
 * 요소의 모습: data-* 속성(테스트 표식)과 글자를 뺀 마크업. 요소 이름·class·style·aria 등 나머지 속성은 남는다.
 * 스타일이 data-* 표식에 걸려 있으면(테스트 장치, spec.md 화면 계약) 여기서는 차이로 보지 않는다.
 */
function presentation(el: Element): string {
  const clone = el.cloneNode(true) as Element;
  const strip = (node: Element) => {
    for (const attr of Array.from(node.attributes)) if (attr.name.startsWith("data-")) node.removeAttribute(attr.name);
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) child.remove();
      else if (child.nodeType === Node.ELEMENT_NODE) strip(child as Element);
    }
  };
  strip(clone);
  return clone.outerHTML;
}

/** 요소와 그 아래 모든 요소의 class 토큰·style 값·요소 이름. */
function styleFacts(el: Element) {
  const nodes = [el, ...Array.from(el.querySelectorAll("*"))];
  return {
    classes: new Set(nodes.flatMap((node) => Array.from(node.classList))),
    styles: nodes.map((node) => node.getAttribute("style") ?? "").join("|"),
    tags: nodes.map((node) => node.tagName.toLowerCase()),
  };
}

const GLOBALS_CSS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../../app/globals.css");

/** 이 프로젝트의 Tailwind 설정(src/app/globals.css)으로 그 class가 실제 CSS 규칙을 만드는지. */
async function generatesCss(token: string): Promise<boolean> {
  const source = fs.readFileSync(GLOBALS_CSS, "utf8");
  const options = { base: path.dirname(GLOBALS_CSS), onDependency: () => {} };
  const empty = (await compile(source, options)).build([]);
  const withToken = (await compile(source, options)).build([token]);
  return withToken !== empty;
}

const EMPHASIS_TAGS = new Set(["strong", "b", "mark", "em"]);

/**
 * 바뀐 행과 안 바뀐 행의 차이가 화면에 실제로 드러나는지(스타일 정의가 있는 차이인지).
 * 인정하는 차이: ① 한쪽에만 있는 class 중 프로젝트 Tailwind가 CSS를 만드는 것, ② inline style 값의 차이,
 * ③ 한쪽에만 있는 강조 요소(strong·b·mark·em, 브라우저 기본 스타일이 있음). 정의 없는 class·data-*만 다르면 실패.
 */
async function visibleDifference(a: Element, b: Element): Promise<string[]> {
  const fa = styleFacts(a);
  const fb = styleFacts(b);
  const reasons: string[] = [];
  const onlyOne = [...fa.classes].filter((c) => !fb.classes.has(c)).concat([...fb.classes].filter((c) => !fa.classes.has(c)));
  for (const token of onlyOne) if (await generatesCss(token)) reasons.push(`class ${token}`);
  if (fa.styles !== fb.styles) reasons.push("style");
  const count = (tags: string[], tag: string) => tags.filter((t) => t === tag).length;
  for (const tag of EMPHASIS_TAGS) if (count(fa.tags, tag) !== count(fb.tags, tag)) reasons.push(`<${tag}>`);
  return reasons;
}

/** Tailwind 변형 접두어(`hover:`·`dark:`·`print:`·`md:`·`data-[…]:` 등)가 붙은 class인가. 대괄호 안의 ':'는 보지 않는다. */
const hasVariant = (token: string) => token.replace(/\[[^\]]*\]/g, "").includes(":");

const cssCache = new Map<string, Promise<string>>();
/** 프로젝트 Tailwind 설정으로 tokens만 쓴 CSS를 만든다. */
function buildCss(tokens: string[]): Promise<string> {
  const key = tokens.join(" ");
  if (!cssCache.has(key)) {
    const source = fs.readFileSync(GLOBALS_CSS, "utf8");
    const options = { base: path.dirname(GLOBALS_CSS), onDependency: () => {} };
    cssCache.set(key, compile(source, options).then((compiler) => compiler.build(tokens)));
  }
  return cssCache.get(key)!;
}

/** css에서 opener("@layer base {" 등) 뒤의 중괄호 블록 안쪽. 없으면 "". */
function blockAfter(css: string, opener: string): string {
  const start = css.indexOf(opener);
  if (start < 0) return "";
  let depth = 0;
  for (let i = start + opener.length - 1; i < css.length; i += 1) {
    if (css[i] === "{") depth += 1;
    else if (css[i] === "}") {
      depth -= 1;
      if (depth === 0) return css.slice(start + opener.length, i);
    }
  }
  return "";
}

/** 블록 안의 "속성: 값" 선언(사용자 정의 속성 --x 제외). */
const declarationsIn = (body: string) =>
  Array.from(body.matchAll(/(?:^|[;{\s])([a-z][a-z-]*)\s*:\s*([^;{}]+);/g), (m) => `${m[1]}: ${m[2].trim()}`);

/** class 하나가 만드는 실제 선언. */
async function utilityDeclarations(token: string): Promise<string[]> {
  return declarationsIn(blockAfter(await buildCss([token]), "@layer utilities {"));
}

/** preflight가 모든 요소(*, ::after, ::before …)에 이미 거는 선언. */
async function preflightDeclarations(): Promise<string[]> {
  const base = blockAfter(await buildCss([]), "@layer base {");
  const universal = /\*\s*,\s*::after[^{]*\{([^}]*)\}/.exec(base);
  return universal ? declarationsIn(`{${universal[1]}}`) : [];
}

/**
 * token(한쪽 행에만 있는 class)이 다른 쪽 행에 이미 적용된 값을 반복하는 데 그치지 않는가(P-50).
 * 다른 쪽 행의 기준 = preflight 선언 + 그 행의 (변형 없는) class 선언 + 요소 기본 display.
 * token의 선언 중 하나라도 기준에 없으면 실제로 모습을 바꾼다.
 */
async function changesAppliedStyle(token: string, other: Element): Promise<boolean> {
  const baseline = new Set(await preflightDeclarations());
  for (const otherToken of styleFacts(other).classes) {
    if (hasVariant(otherToken)) continue;
    for (const declaration of await utilityDeclarations(otherToken)) baseline.add(declaration);
  }
  for (const node of [other, ...Array.from(other.querySelectorAll("*"))]) {
    baseline.add(`display: ${window.getComputedStyle(node).display}`);
  }
  return (await utilityDeclarations(token)).some((declaration) => !baseline.has(declaration));
}

/**
 * 평소 화면(마우스·인쇄·다크 모드·화면 폭 조건 없이)에 바로 드러나는 차이만 센다(P-50).
 * visibleDifference에서 변형 접두어가 붙은 class와, 이미 적용된 값(preflight·다른 쪽 행의 class·기본 display)을
 * 반복할 뿐인 class(예: preflight가 이미 거는 box-border)를 뺀 것이다.
 */
async function everydayDifference(a: Element, b: Element): Promise<string[]> {
  const aClasses = styleFacts(a).classes;
  const reasons: string[] = [];
  for (const reason of await visibleDifference(a, b)) {
    if (!reason.startsWith("class ")) {
      reasons.push(reason);
      continue;
    }
    const token = reason.slice(6);
    if (hasVariant(token)) continue;
    if (await changesAppliedStyle(token, aClasses.has(token) ? b : a)) reasons.push(reason);
  }
  return reasons;
}

async function mountWith(f4: unknown, id = 1031) {
  const api = makeApi({
    F2: [page([userMsg(id - 1), recalc(id)], false)],
    [`F4 ${id}`]: [f4],
  });
  const env = makeStore();
  await mountChat(api, env.store);
  return { api, ...env };
}

describe("AC-11 카드 문구는 문구표 키로, 행 순서는 conditions 고정 4키 순서 (C12b-6, C2-2)", () => {
  it("조건 4행이 monthlySavingAmount·savingsRatioPct·savingsRateInputPct·targetPeriodMonths 순서로, 라벨은 condition.<키>, 값은 display 그대로다", async () => {
    const body = comparison("get-comparison.200.json", { changedKeys: ["targetPeriodMonths", "monthlySavingAmount"] });
    // 응답 객체의 키 순서를 뒤집어도 행 순서는 고정 4키 순서다
    for (const side of ["current", "changed"] as const) {
      body.conditions[side] = Object.fromEntries(Object.entries(body.conditions[side]).reverse());
    }
    await mountWith(body);

    const rows = conditionRows(1031);
    expect(rows.map((row) => row.getAttribute("data-key"))).toEqual(CONDITION_KEYS);
    for (const row of rows) {
      const key = row.getAttribute("data-key")!;
      expect(row.textContent).toContain(copy[`condition.${key}`]);
      expect(row.textContent).toContain(body.display[`conditions.current.${key}`]);
      expect(row.textContent).toContain(body.display[`conditions.changed.${key}`]);
    }
  });

  it("changedKeys(순서 없음)에 든 행만 강조된다(집합 비교)", async () => {
    const body = comparison("get-comparison.200.json", { changedKeys: ["targetPeriodMonths", "monthlySavingAmount"] });
    await mountWith(body);
    const changed = Object.fromEntries(conditionRows(1031).map((row) => [row.getAttribute("data-key"), row.getAttribute("data-changed")]));
    expect(changed).toEqual({
      monthlySavingAmount: "true",
      savingsRatioPct: "false",
      savingsRateInputPct: "false",
      targetPeriodMonths: "true",
    });
  });

  it("changedKeys가 저축 비중·적금 금리(순서 없음)이면 그 두 행만 강조되고, 강조는 평소 화면에 드러난다", async () => {
    await mountWith(comparison("get-comparison.200.json", { changedKeys: ["savingsRateInputPct", "savingsRatioPct"] }));
    const rowList = conditionRows(1031);
    expect(rowList.map((row) => row.getAttribute("data-key"))).toEqual(CONDITION_KEYS);
    const changed = Object.fromEntries(rowList.map((row) => [row.getAttribute("data-key"), row.getAttribute("data-changed")]));
    expect(changed).toEqual({
      monthlySavingAmount: "false",
      savingsRatioPct: "true",
      savingsRateInputPct: "true",
      targetPeriodMonths: "false",
    });

    // 실제 모습도 그 두 행만 다르다
    const look = Object.fromEntries(rowList.map((row) => [row.getAttribute("data-key"), presentation(row)]));
    expect(look.savingsRatioPct).toBe(look.savingsRateInputPct);
    expect(look.monthlySavingAmount).toBe(look.targetPeriodMonths);
    expect(look.savingsRatioPct, "저축 비중·적금 금리가 바뀌었는데 강조 모습이 없다").not.toBe(look.monthlySavingAmount);

    const rows = Object.fromEntries(rowList.map((row) => [row.getAttribute("data-key"), row]));
    vi.useRealTimers(); // Tailwind 컴파일은 실제 파일 읽기를 쓴다
    for (const changedKey of ["savingsRatioPct", "savingsRateInputPct"]) {
      for (const plainKey of ["monthlySavingAmount", "targetPeriodMonths"]) {
        const reasons = await everydayDifference(rows[changedKey], rows[plainKey]);
        expect(reasons, `${changedKey}(바뀜)와 ${plainKey}(안 바뀜)의 차이가 평소 화면에 드러나지 않는다`).not.toEqual([]);
      }
    }
    expect(await visibleDifference(rows.savingsRatioPct, rows.savingsRateInputPct)).toEqual([]);
    expect(await visibleDifference(rows.monthlySavingAmount, rows.targetPeriodMonths)).toEqual([]);
  });

  it("changedKeys가 빈 배열이면 강조된 행이 없다", async () => {
    await mountWith(comparison("get-comparison.200.json", { changedKeys: [] }));
    expect(conditionRows(1031)).toHaveLength(4);
    expect(conditionRows(1031).filter((row) => row.getAttribute("data-changed") === "true")).toHaveLength(0);
  });

  it("강조는 사용자에게 보이는 차이다: data-* 표식과 글자를 뺀 행 마크업이 바뀐 행과 안 바뀐 행 사이에 다르고, changedKeys가 비면 4행이 모두 같다", async () => {
    await mountWith(comparison("get-comparison.200.json", { changedKeys: ["targetPeriodMonths", "monthlySavingAmount"] }));
    const highlighted = Object.fromEntries(conditionRows(1031).map((row) => [row.getAttribute("data-key"), presentation(row)]));
    expect(Object.keys(highlighted)).toEqual(CONDITION_KEYS);
    expect(highlighted.monthlySavingAmount).toBe(highlighted.targetPeriodMonths);
    expect(highlighted.savingsRatioPct).toBe(highlighted.savingsRateInputPct);
    expect(highlighted.monthlySavingAmount, "바뀐 행과 안 바뀐 행의 모습(class·style·요소)이 같다").not.toBe(highlighted.savingsRatioPct);

    cleanup();
    await mountWith(comparison("get-comparison.200.json", { changedKeys: [] }));
    const plain = conditionRows(1031).map(presentation);
    expect(plain).toHaveLength(4);
    expect(new Set(plain).size).toBe(1);
    expect(plain[0]).toBe(highlighted.savingsRatioPct);
  });

  it("강조에는 스타일 정의가 있다: 바뀐 행과 안 바뀐 행의 차이가 프로젝트 Tailwind가 CSS를 만드는 class·inline style·강조 요소(strong·b·mark·em) 중 하나다(정의 없는 class만 다르면 실패)", async () => {
    await mountWith(comparison("get-comparison.200.json", { changedKeys: ["targetPeriodMonths", "monthlySavingAmount"] }));
    const rows = Object.fromEntries(conditionRows(1031).map((row) => [row.getAttribute("data-key"), row]));
    expect(Object.keys(rows)).toEqual(CONDITION_KEYS);
    vi.useRealTimers(); // Tailwind 컴파일은 실제 파일 읽기를 쓴다
    for (const changedKey of ["monthlySavingAmount", "targetPeriodMonths"]) {
      for (const plainKey of ["savingsRatioPct", "savingsRateInputPct"]) {
        const reasons = await visibleDifference(rows[changedKey], rows[plainKey]);
        expect(reasons, `${changedKey}(바뀜)와 ${plainKey}(안 바뀜)의 차이에 스타일 정의가 없다`).not.toEqual([]);
      }
    }
    // 같은 쪽끼리는 스타일 차이가 없다
    expect(await visibleDifference(rows.monthlySavingAmount, rows.targetPeriodMonths)).toEqual([]);
    expect(await visibleDifference(rows.savingsRatioPct, rows.savingsRateInputPct)).toEqual([]);
  });

  it("강조에는 평소 화면에 적용되는 스타일 정의가 있다: hover:·dark:·print:·md: 같은 변형으로만 생기는 class 차이는 강조로 보지 않는다(P-50)", async () => {
    await mountWith(comparison("get-comparison.200.json", { changedKeys: ["targetPeriodMonths", "monthlySavingAmount"] }));
    const rows = Object.fromEntries(conditionRows(1031).map((row) => [row.getAttribute("data-key"), row]));
    expect(Object.keys(rows)).toEqual(CONDITION_KEYS);
    vi.useRealTimers(); // Tailwind 컴파일은 실제 파일 읽기를 쓴다

    // 판정 도구 확인: 조건 없는 class는 인정, 변형 class·정의 없는 class는 Tailwind가 CSS를 만들어도/못 만들어도 불인정
    const counts = async (token: string) => !hasVariant(token) && (await generatesCss(token));
    for (const token of ["font-bold", "bg-primary-soft"]) expect(await counts(token), `${token} 인정`).toBe(true);
    for (const token of ["hover:font-bold", "print:font-bold", "md:bg-primary-soft", "data-[changed=true]:font-bold", "is-changed"]) {
      expect(await counts(token), `${token} 불인정`).toBe(false);
    }

    for (const changedKey of ["monthlySavingAmount", "targetPeriodMonths"]) {
      for (const plainKey of ["savingsRatioPct", "savingsRateInputPct"]) {
        const reasons = await everydayDifference(rows[changedKey], rows[plainKey]);
        expect(reasons, `${changedKey}(바뀜)와 ${plainKey}(안 바뀜)의 차이가 평소 화면에 드러나지 않는다(변형 class뿐)`).not.toEqual([]);
      }
    }
  });

  it("강조 차이는 이미 적용된 값을 반복하는 class로는 인정하지 않는다: preflight·안 바뀐 행의 class·요소 기본 display와 같은 값만 더하는 class(box-border 등)만 다르면 실패(P-50)", async () => {
    vi.useRealTimers(); // Tailwind 컴파일은 실제 파일 읽기를 쓴다

    // 판정 도구 확인: 같은 구조의 두 행(문서에 붙임)에서 한쪽에만 class를 더해 본다
    const sandbox = document.createElement("div");
    document.body.appendChild(sandbox);
    const pair = (extra: string, base = "flex gap-2") => {
      const plain = document.createElement("div");
      plain.className = base;
      plain.innerHTML = "<span>a</span><span>b</span>";
      const changed = plain.cloneNode(true) as HTMLElement;
      changed.className = `${base} ${extra}`;
      sandbox.append(changed, plain);
      return [changed, plain] as const;
    };
    // preflight가 모든 요소에 이미 box-sizing: border-box를 건다
    expect(await everydayDifference(...pair("box-border")), "box-border는 이미 적용된 값이다").toEqual([]);
    // div의 기본 display는 block이다
    expect(await everydayDifference(...pair("block", "gap-2")), "div의 block은 이미 적용된 값이다").toEqual([]);
    // 실제로 모습을 바꾸는 class는 인정한다
    expect(await everydayDifference(...pair("font-bold"))).toEqual(["class font-bold"]);
    expect(await everydayDifference(...pair("bg-primary-soft"))).toEqual(["class bg-primary-soft"]);
    sandbox.remove();

    vi.useFakeTimers();
    await mountWith(comparison("get-comparison.200.json", { changedKeys: ["targetPeriodMonths", "monthlySavingAmount"] }));
    const rows = Object.fromEntries(conditionRows(1031).map((row) => [row.getAttribute("data-key"), row]));
    expect(Object.keys(rows)).toEqual(CONDITION_KEYS);
    vi.useRealTimers();

    for (const changedKey of ["monthlySavingAmount", "targetPeriodMonths"]) {
      for (const plainKey of ["savingsRatioPct", "savingsRateInputPct"]) {
        const reasons = await everydayDifference(rows[changedKey], rows[plainKey]);
        expect(reasons, `${changedKey}(바뀜)와 ${plainKey}(안 바뀜)의 차이가 이미 적용된 값의 반복뿐이다`).not.toEqual([]);
      }
    }
  });

  it("시나리오마다 diff.byScenario[].achievementChange를 문구표 achievementChange.<값>으로 그린다(4값)", async () => {
    const body = comparison("get-comparison.200.json");
    body.diff.byScenario[0].achievementChange = "STAYS_ACHIEVED";
    body.diff.byScenario[1].achievementChange = "ACHIEVE_TO_MISS";
    body.diff.byScenario[2].achievementChange = "STAYS_MISSED";
    await mountWith(body);
    const rows = scenarioRows(1031);
    const byScenario = Object.fromEntries(rows.map((row) => [row.getAttribute("data-scenario"), row.textContent ?? ""]));
    expect(byScenario.GOOD).toContain(copy["achievementChange.STAYS_ACHIEVED"]);
    expect(byScenario.AVG).toContain(copy["achievementChange.ACHIEVE_TO_MISS"]);
    expect(byScenario.BAD).toContain(copy["achievementChange.STAYS_MISSED"]);

    cleanup();
    await mountWith(comparison("get-comparison.200.json"));
    for (const row of scenarioRows(1031)) expect(row.textContent).toContain(copy["achievementChange.MISS_TO_ACHIEVE"]);
  });

  it("숫자는 display 문자열만 쓴다: 원시 숫자(500000·7890000·25010000)가 카드에 없다", async () => {
    await mountWith(comparison("get-comparison.200.json"));
    const text = cardOf(1031).textContent ?? "";
    for (const raw of ["300000", "500000", "7890000", "25010000", "17120000"]) expect(text).not.toContain(raw);
  });
});

describe("AC-12 시나리오 개수 (C12b-6, ETF 0%)", () => {
  it("get-comparison.200-etf0(1033): 시나리오 AVG 1개짜리 카드가 그려진다", async () => {
    await mountWith(comparison("get-comparison.200-etf0.json"), 1033);
    expect(card(1033)).not.toBeNull();
    expect(scenarioRows(1033).map((row) => row.getAttribute("data-scenario"))).toEqual(["AVG"]);
    expect(conditionRows(1033)).toHaveLength(4);
    expect(buttonOf(1033).disabled).toBe(false);
  });

  it("get-comparison.200(1031): 시나리오 3개짜리 카드가 그려진다", async () => {
    await mountWith(comparison("get-comparison.200.json"));
    expect(
      scenarioRows(1031)
        .map((row) => row.getAttribute("data-scenario"))
        .sort(),
    ).toEqual(["AVG", "BAD", "GOOD"]);
  });
});

describe("AC-13 F-4 오류 (C12b-6 오류 표)", () => {
  it("404 NOT_FOUND: 카드 자리가 비고 버튼이 없다", async () => {
    const { api } = await mountWith(problem(404, "NOT_FOUND"));
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(card(1031)).toBeNull();
    expect(failedNotice(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(0);
  });

  it("400 MALFORMED_REQUEST: 404와 같다(카드 자리 비움, 버튼 없음)", async () => {
    const { api } = await mountWith(malformed());
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(card(1031)).toBeNull();
    expect(failedNotice(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(0);
  });

  it("404·400 뒤 대화창은 비교 UI가 없는 기준(같은 본문의 ANSWER 메시지)과 마크업·글자가 같다: 별도 안내나 빈 카드 외형이 없고, 같은 비교가 성공하면 기준과 달라진다", async () => {
    const content = "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요";
    const baselineApi = makeApi({ F2: [page([userMsg(1030), doneMsg(1031, "ANSWER", content)], false)] });
    await mountChat(baselineApi, makeStore().store);
    expect(baselineApi.f4Calls()).toEqual([]);
    const baseline = presentation(screen.getByTestId("chat-window"));
    const baselineText = screen.getByTestId("chat-window").textContent;
    cleanup();

    for (const [label, error] of [
      ["404 NOT_FOUND", problem(404, "NOT_FOUND")],
      ["400 MALFORMED_REQUEST", malformed()],
    ] as const) {
      const { api } = await mountWith(error);
      expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
      expect(presentation(screen.getByTestId("chat-window")), `F-4 ${label} 뒤 비교 자리에 요소가 그려져 있다`).toBe(baseline);
      expect(screen.getByTestId("chat-window").textContent, `F-4 ${label} 뒤 비교 자리에 글자가 있다`).toBe(baselineText);
      cleanup();
    }

    // 같은 비교가 성공하면 기준과 달라진다(이 판정이 카드 자리를 실제로 본다는 확인)
    await mountWith(comparison("get-comparison.200.json"));
    expect(presentation(screen.getByTestId("chat-window"))).not.toBe(baseline);
  });

  it("500 INTERNAL_ERROR: 카드 자리에 문구표 comparison.loadFailed, 버튼 없음, 자동 재시도 없음", async () => {
    const { api } = await mountWith(serverError());
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    expect(applyButtons()).toHaveLength(0);
    await tick(30_000);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(applyButtons()).toHaveLength(0);
  });

  it("500 뒤 새로고침하면 다시 시도한다: 열린 대화창은 다시 부르지 않고, 새로 연 대화창은 같은 최신 비교의 F-4를 1회 불러 카드를 그린다", async () => {
    const failed = await mountWith(serverError());
    await tick(30_000);
    expect(failed.api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    cleanup();

    // 새로고침: 대화창·store를 새로 만들고 같은 메시지 목록을 복원한다
    const reloaded = await mountWith(comparison("get-comparison.200.json"));
    expect(reloaded.api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(failedNotice(1031)).toBeNull();
    expect(cardOf(1031).getAttribute("data-collapsed")).toBe("false");
    expect(conditionRows(1031)).toHaveLength(4);
    expect(buttonOf(1031).disabled).toBe(false);
  });

  // HTTP 오류 코드 없이 실패(연결 실패, 잘못된 JSON, HTML 오류 응답)하면 진짜 래퍼가 code 없는 네트워크 오류로 넘긴다(P-51).
  async function expectCodelessFailureLikeServerError(respondF4: () => Response) {
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") {
        return jsonResponse(200, page([userMsg(1030), recalc(1031)], false));
      }
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") return respondF4();
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    const f4Fetches = () => env.fetchStub.mock.calls.filter(([input]) => String(input).endsWith("/comparison")).length;
    await mountChat(env.api, env.store);
    await tick(0);
    await flush();
    expect(f4Fetches()).toBe(1);
    expect(failedNotice(1031)?.textContent, "코드 없는 F-4 실패에 '비교 결과를 불러오지 못했어요'가 없다").toContain(copy["comparison.loadFailed"]);
    expect(card(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(0);
    await tick(30_000); // 자동 재시도 없음
    await flush();
    expect(f4Fetches()).toBe(1);
    expect(applyButtons()).toHaveLength(0);
    expect(env.navigations).toEqual([]);
  }

  it("코드 없는 실패 — 연결 실패(fetch 거절): 500과 같이 카드 자리에 comparison.loadFailed, 버튼 없음, 자동 재시도 없음", async () => {
    await expectCodelessFailureLikeServerError(() => {
      throw new TypeError("Failed to fetch");
    });
  });

  it("코드 없는 실패 — 파싱할 수 없는 JSON(200 application/json): 500과 같이 카드 자리에 comparison.loadFailed, 버튼 없음, 자동 재시도 없음", async () => {
    await expectCodelessFailureLikeServerError(
      () => new Response("{not json", { status: 200, headers: { "Content-Type": "application/json" } }),
    );
  });

  it("코드 없는 실패 — HTML 오류 응답(502 text/html): 500과 같이 카드 자리에 comparison.loadFailed, 버튼 없음, 자동 재시도 없음", async () => {
    await expectCodelessFailureLikeServerError(
      () => new Response("<html><body>Bad Gateway</body></html>", { status: 502, headers: { "Content-Type": "text/html" } }),
    );
  });

  // 파싱은 되지만 카드에 필요한 데이터가 없거나 형태가 틀린 200도 500과 같이 처리한다(P-59, 사용자 결정 2026-11-20).
  async function expectMalformedCardLikeServerError(override: Record<string, unknown>) {
    const broken = JSON.stringify({ ...comparison("get-comparison.200.json"), ...override });
    await expectCodelessFailureLikeServerError(
      () => new Response(broken, { status: 200, headers: { "Content-Type": "application/json" } }),
    );
    expect(screen.getByTestId("chat-window")).toBeTruthy();
  }

  it("200인데 카드 데이터가 없거나 형태가 틀림 — display가 null: 500과 같이 comparison.loadFailed, 카드·버튼 없음, 자동 재시도 없음, 대화창 유지", async () => {
    await expectMalformedCardLikeServerError({ display: null });
  });

  it("200인데 카드 데이터가 없거나 형태가 틀림 — diff.byScenario가 배열이 아님: 500과 같이 comparison.loadFailed, 카드·버튼 없음, 자동 재시도 없음, 대화창 유지", async () => {
    await expectMalformedCardLikeServerError({ diff: { byScenario: "AVG" } });
  });

  it("200인데 카드 데이터가 없거나 형태가 틀림 — conditions 없음: 500과 같이 comparison.loadFailed, 카드·버튼 없음, 자동 재시도 없음, 대화창 유지", async () => {
    await expectMalformedCardLikeServerError({ conditions: undefined });
  });

  it("401 UNAUTHENTICATED: 로그인 화면으로 가고 카드·버튼이 없다(래퍼 #13)", async () => {
    const env = makeLiveEnv((method, requestPath) => {
      if (method === "GET" && requestPath === "/api/chat/messages") {
        return jsonResponse(200, page([userMsg(1030), recalc(1031)], false));
      }
      if (method === "GET" && requestPath === "/api/chat/messages/1031/comparison") {
        return problemResponse(401, "UNAUTHENTICATED");
      }
      throw new Error(`예상 밖 호출: ${method} ${requestPath}`);
    });
    await mountChat(env.api, env.store);
    await tick(0);
    expect(env.navigations).toContain(LOGIN_PATH);
    expect(cards()).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
  });
});

describe("AC-2 비교 결과가 뜬 뒤 적용 전에는 내 계획이 그대로다 (FR-AI-06, D-022)", () => {
  it("카드가 그려져도 공유 store는 같은 상태이고, 계획 조회·적용 호출이 없다", async () => {
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [comparison("get-comparison.200.json")],
    });
    const { store, planGets } = makeStore();
    const before = store.getState();
    await mountChat(api, store);
    await tick(5000);

    expect(card(1031)).not.toBeNull();
    expect(buttonOf(1031).disabled).toBe(false);
    expect(store.getState()).toBe(before);
    expect(store.getState().revision).toBe(12);
    expect(planGets).toHaveLength(0);
    expect(api.f5Calls()).toHaveLength(0);
    expect(api.calls.filter((c) => c.method !== "GET")).toHaveLength(0);
  });
});

describe("AC-23 최신이던 카드의 F-4가 실패했는데 새 RECALC_RESULT가 오면 (사용자 결정 U-2=B)", () => {
  async function mountFailedThenNew(f4Failure: unknown) {
    const api = makeApi({
      F2: [
        page([userMsg(1030), recalc(1031), userMsg(1032), pendingMsg(1033)], true),
        page([recalc(1033)], false),
      ],
      "F4 1031": [f4Failure],
      "F4 1033": [comparison("get-comparison.200.json", { messageId: 1033 })],
    });
    const env = makeStore();
    await mountChat(api, env.store);
    await tick(1000); // 폴링: 1033 RECALC_RESULT 도착
    await flush();
    await flush();
    return api;
  }

  async function expectEmptySlotKept(failure: unknown) {
    const api = await mountFailedThenNew(failure);
    expect(cardOf(1033).getAttribute("data-collapsed")).toBe("false");
    expect(card(1031)).toBeNull();
    expect(failedNotice(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(1);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
  }

  it("404 NOT_FOUND: 그 자리는 빈 채로 남고 '이전 결과' 접힌 카드로 바뀌지 않는다", async () => {
    await expectEmptySlotKept(problem(404, "NOT_FOUND"));
  });

  it("400 MALFORMED_REQUEST: 그 자리는 빈 채로 남고 '이전 결과' 접힌 카드로 바뀌지 않는다", async () => {
    await expectEmptySlotKept(malformed());
  });

  it("500 INTERNAL_ERROR: 안내 문구가 그대로 남고 '이전 결과' 접힌 카드로 바뀌지 않는다", async () => {
    const api = await mountFailedThenNew(serverError());
    expect(cardOf(1033).getAttribute("data-collapsed")).toBe("false");
    expect(failedNotice(1031)?.textContent).toContain(copy["comparison.loadFailed"]);
    expect(card(1031)).toBeNull();
    expect(applyButtons()).toHaveLength(1);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison", "/api/chat/messages/1033/comparison"]);
  });
});

describe("AC-24 카드의 숫자 항목과 F-4 응답 전 자리 (사용자 결정 U-4=A)", () => {
  it("F-4 응답 전에는 자리가 비고, 응답 뒤 시나리오마다 세후 자산(현재→바꾼 조건)·자산 차이·목표 대비를 display 그대로 보인다", async () => {
    const f4 = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [f4.promise],
    });
    const env = makeStore();
    await mountChat(api, env.store);
    expect(conditionRows(1031)).toHaveLength(0);
    expect(scenarioRows(1031)).toHaveLength(0);
    expect(applyButtons()).toHaveLength(0);
    expect(failedNotice(1031)).toBeNull();

    const body = comparison("get-comparison.200.json");
    f4.resolve(body);
    await flush();
    await flush();
    const rows = scenarioRows(1031);
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      const s = row.getAttribute("data-scenario")!;
      const text = row.textContent ?? "";
      for (const key of [`current.${s}.afterTaxAsset`, `changed.${s}.afterTaxAsset`, `diff.${s}.afterTaxAssetDelta`, `changed.${s}.gapToGoal`]) {
        expect(text, `${s} 행에 display["${key}"]가 없다`).toContain(body.display[key]);
      }
    }
  });

  it("칸마다 맞는 값: 서로 다른 display 값으로 현재·바꾼 조건·자산 차이·목표 대비·달성 변화가 각자 자기 칸에 있다(뒤바뀜 없음)", async () => {
    const body = comparison("get-comparison.200.json", { changedKeys: ["monthlySavingAmount"] });
    // 모든 display 값을 키마다 다른 글자로 바꿔, 값이 다른 칸에 들어가면 드러나게 한다
    for (const key of Object.keys(body.display)) body.display[key] = `[${key}]`;
    body.diff.byScenario[0].achievementChange = "STAYS_ACHIEVED";
    body.diff.byScenario[1].achievementChange = "ACHIEVE_TO_MISS";
    body.diff.byScenario[2].achievementChange = "STAYS_MISSED";
    await mountWith(body);

    const rows = conditionRows(1031);
    expect(rows).toHaveLength(4);
    for (const row of rows) {
      const key = row.getAttribute("data-key")!;
      expect(cellText(row, "chat-comparison-label"), `${key} 라벨 칸`).toBe(copy[`condition.${key}`]);
      expect(cellText(row, "chat-comparison-current"), `${key} 현재 칸`).toBe(`[conditions.current.${key}]`);
      expect(cellText(row, "chat-comparison-changed"), `${key} 바꾼 조건 칸`).toBe(`[conditions.changed.${key}]`);
    }

    const achievement: Record<string, string> = {};
    for (const item of body.diff.byScenario) achievement[item.scenario] = item.achievementChange;
    const scenarios = scenarioRows(1031);
    expect(scenarios).toHaveLength(3);
    for (const row of scenarios) {
      const s = row.getAttribute("data-scenario")!;
      expect(cellText(row, "chat-comparison-current"), `${s} 현재 세후 자산 칸`).toBe(`[current.${s}.afterTaxAsset]`);
      expect(cellText(row, "chat-comparison-changed"), `${s} 바꾼 조건 세후 자산 칸`).toBe(`[changed.${s}.afterTaxAsset]`);
      expect(cellText(row, "chat-comparison-delta"), `${s} 자산 차이 칸`).toBe(`[diff.${s}.afterTaxAssetDelta]`);
      expect(cellText(row, "chat-comparison-gap"), `${s} 목표 대비 칸`).toBe(`[changed.${s}.gapToGoal]`);
      expect(cellText(row, "chat-comparison-achievement"), `${s} 달성 변화 칸`).toBe(copy[`achievementChange.${achievement[s]}`]);
    }
  });

  it("F-4 응답 전 그 메시지의 비교 자리에는 보이는 것이 없다: 로딩 글자·표시 없이 F-4 404(빈 자리)일 때와 대화창이 같다", async () => {
    const busy = '[role="progressbar"], [aria-busy="true"], [role="status"]';
    const f4 = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [f4.promise],
    });
    await mountChat(api, makeStore().store);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    const waiting = {
      text: screen.getByTestId("chat-window").textContent,
      busy: document.querySelectorAll(busy).length,
    };
    expect(card(1031)?.textContent?.trim() ?? "").toBe("");
    cleanup();

    await mountWith(problem(404, "NOT_FOUND"));
    const empty = {
      text: screen.getByTestId("chat-window").textContent,
      busy: document.querySelectorAll(busy).length,
    };
    expect(waiting).toEqual(empty);
  });

  it("F-4 응답 전 대화창 마크업은 F-4 404(빈 자리)일 때와 같다: data-* 표식을 빼고 비교하므로 글자·ARIA 없는 스켈레톤도 드러난다", async () => {
    const f4 = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [f4.promise],
    });
    await mountChat(api, makeStore().store);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    const waiting = presentation(screen.getByTestId("chat-window"));
    cleanup();

    await mountWith(problem(404, "NOT_FOUND"));
    const empty = presentation(screen.getByTestId("chat-window"));
    expect(waiting).toBe(empty);

    // 같은 비교가 성공하면 마크업이 달라진다(이 판정이 카드 자리를 실제로 본다는 확인)
    cleanup();
    await mountWith(comparison("get-comparison.200.json"));
    expect(presentation(screen.getByTestId("chat-window"))).not.toBe(empty);
  });

  it("비교 UI가 없는 기준(같은 본문의 ANSWER 메시지)과 대조: F-4 대기 중에도, 404 뒤에도 대화창 마크업이 기준과 같다(같은 스켈레톤·빈 래퍼를 두 상태에 그려도 실패)", async () => {
    const content = "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요";
    // 기준: 비교 카드가 생기지 않는 메시지(ANSWER, AC-6). F-4를 부르지 않는다.
    const baselineApi = makeApi({ F2: [page([userMsg(1030), doneMsg(1031, "ANSWER", content)], false)] });
    await mountChat(baselineApi, makeStore().store);
    expect(baselineApi.f4Calls()).toEqual([]);
    const baseline = presentation(screen.getByTestId("chat-window"));
    const baselineText = screen.getByTestId("chat-window").textContent;
    cleanup();

    const f4 = deferred<unknown>();
    const api = makeApi({
      F2: [page([userMsg(1030), recalc(1031)], false)],
      "F4 1031": [f4.promise],
    });
    await mountChat(api, makeStore().store);
    expect(api.f4Calls()).toEqual(["/api/chat/messages/1031/comparison"]);
    expect(presentation(screen.getByTestId("chat-window")), "F-4 대기 중 비교 자리에 요소가 그려져 있다").toBe(baseline);

    f4.reject(problem(404, "NOT_FOUND"));
    await flush();
    await flush();
    expect(presentation(screen.getByTestId("chat-window")), "F-4 404 뒤 비교 자리에 요소가 남아 있다").toBe(baseline);
    expect(screen.getByTestId("chat-window").textContent).toBe(baselineText);
  });

  it("ETF 0%(get-comparison.200-etf0, 1033)도 AVG 행에 같은 4개 display 값이 있다", async () => {
    const body = comparison("get-comparison.200-etf0.json");
    await mountWith(body, 1033);
    const [row] = scenarioRows(1033);
    expect(row?.getAttribute("data-scenario")).toBe("AVG");
    for (const key of ["current.AVG.afterTaxAsset", "changed.AVG.afterTaxAsset", "diff.AVG.afterTaxAssetDelta", "changed.AVG.gapToGoal"]) {
      expect(row?.textContent ?? "").toContain(body.display[key]);
    }
  });
});
