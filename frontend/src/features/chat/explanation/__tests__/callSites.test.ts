// Issue #54 정적 규칙: F-3 호출 지점은 대화창 effect 하나(C12b-3a, H-11c), GET /api/plan 직접 호출 없음(C12a-6-5, J-12f).
// 결정 3 T-4: features/chat/index.ts 재노출을 거친 호출도 잡는다. 결정 4 B2: F-3 요청 기능의 재노출·우회 호출만 막고,
// F-3과 무관한 대화 모듈 사용(ChatWindow 지연 로딩, 다른 이름 가져오기)은 막지 않는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, "../../../..");
const CHAT = path.join(SRC, "features", "chat");
const EXPLANATION = path.join(CHAT, "explanation");
const CHAT_WINDOW = path.join(CHAT, "ChatWindow.tsx");
const F3 = "/api/chat/explanations";
// 목 전송 계층의 요청 키 표(개발·테스트 전용, 화면 코드가 아님).
const NOT_APP_CODE = [path.join(SRC, "api", "mock.ts")];

function walk(dir: string): string[] {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__tests__" || full === path.join(SRC, "mocks") ? [] : walk(full);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name) ? [full] : [];
  });
}

const stripComments = (text: string) => text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");

function appSources(): Map<string, string> {
  const files = new Map<string, string>();
  for (const file of walk(SRC)) {
    if (NOT_APP_CODE.includes(file)) continue;
    files.set(file, stripComments(fs.readFileSync(file, "utf8")));
  }
  return files;
}

// ---------------------------------------------------------------- 가져오기 분석(이름 단위, 결정 4 B2)
// 항목: imported = 가져오거나 내보내는 원래 이름("default", 네임스페이스는 "*"), name = 이 모듈에서 붙는 이름(가져오기면 지역 이름, 내보내기면 공개 이름)
type Item = { imported: string; name: string };
type Edge = { kind: "import" | "export" | "dynamic"; specifier: string; typeOnly: boolean; star: boolean; items: Item[]; target: string | null; at: number };
type Decl = { name: string; exportName: string | null; text: string; start: number };
type ModuleInfo = { edges: Edge[]; decls: Decl[]; localExports: Item[] };

function resolveSpecifier(from: string, specifier: string, files: Map<string, string>, srcRoot: string): string | null {
  let base: string;
  if (specifier.startsWith(".")) base = path.resolve(path.dirname(from), specifier);
  else if (specifier.startsWith("@/")) base = path.resolve(srcRoot, specifier.slice(2));
  else return null;
  base = base.replace(/\.js$/, "");
  const candidates = [base, `${base}.ts`, `${base}.tsx`, path.join(base, "index.ts"), path.join(base, "index.tsx")];
  return candidates.find((candidate) => files.has(candidate)) ?? null;
}

function parseClause(clause: string, kind: "import" | "export"): { star: boolean; items: Item[] } {
  const items: Item[] = [];
  const text = clause.trim();
  const namespace = text.match(/\*\s+as\s+(\w+)/);
  if (namespace) items.push({ imported: "*", name: namespace[1] });
  else if (/^\*$/.test(text)) return { star: true, items };
  const braces = text.match(/\{([^}]*)\}/);
  if (braces) {
    for (const raw of braces[1].split(",")) {
      const part = raw.trim();
      if (part === "" || /^type\s/.test(part)) continue;
      const m = part.match(/^(\w+)(?:\s+as\s+(\w+))?$/);
      if (m) items.push({ imported: m[1], name: m[2] ?? m[1] });
    }
  }
  const head = text.replace(/\{[^}]*\}/, "").replace(/\*\s+as\s+\w+/, "").replace(/,/g, " ").trim();
  if (kind === "import" && /^\w+$/.test(head)) items.push({ imported: "default", name: head });
  return { star: false, items };
}

/** 최상위 선언 한 문장. 함수·클래스는 본문 끝 `}`까지, 그 밖은 깊이 0의 `;` 또는 다음 최상위 문장 앞까지. */
function statementAt(text: string, start: number, block: boolean): string {
  let depth = 0;
  let body = false;
  for (let i = start; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === "(" || ch === "[" || ch === "{") {
      if (ch === "{" && depth === 0) body = true;
      depth += 1;
    } else if (ch === ")" || ch === "]" || ch === "}") {
      depth -= 1;
      if (depth < 0) return text.slice(start, i); // 감싼 괄호가 먼저 닫힘(안쪽 선언이 for(…)·인자 안에 있음)
      if (block && body && depth === 0 && ch === "}") return text.slice(start, i + 1);
    } else if (depth === 0 && ch === ";") {
      return text.slice(start, i + 1);
    } else if (depth === 0 && ch === "\n" && /^(export|import|function|const|let|var|class|type|interface|async)\b/.test(text.slice(i + 1))) {
      return text.slice(start, i);
    }
  }
  return text.slice(start);
}

function moduleInfo(file: string, text: string, files: Map<string, string>, srcRoot: string): ModuleInfo {
  const edges: Edge[] = [];
  for (const match of text.matchAll(/\b(import|export)\s+(type\s+)?([^;=()"']*?)\s+from\s+["']([^"']+)["']/g)) {
    const [, kind, typeWord, clause, specifier] = match;
    const parsed = parseClause(clause, kind as "import" | "export");
    edges.push({ kind: kind as "import" | "export", specifier, typeOnly: Boolean(typeWord), ...parsed, target: resolveSpecifier(file, specifier, files, srcRoot), at: match.index ?? 0 });
  }
  for (const match of text.matchAll(/\bimport\s*\(\s*["']([^"']+)["']\s*\)/g)) {
    edges.push({ kind: "dynamic", specifier: match[1], typeOnly: false, star: true, items: [], target: resolveSpecifier(file, match[1], files, srcRoot), at: match.index ?? 0 });
  }
  const decls: Decl[] = [];
  for (const match of text.matchAll(/^(export\s+)?(default\s+)?(?:async\s+)?(function\*?|const|let|var|class)\s+(\w+)/gm)) {
    const [, exported, isDefault, keyword, name] = match;
    const block = keyword.startsWith("function") || keyword === "class";
    decls.push({ name, exportName: exported ? (isDefault ? "default" : name) : null, text: statementAt(text, match.index ?? 0, block), start: match.index ?? 0 });
  }
  const localExports: Item[] = [];
  for (const match of text.matchAll(/\bexport\s*(type\s+)?\{([^}]*)\}(?!\s*from\b)/g)) {
    if (match[1]) continue;
    localExports.push(...parseClause(`{${match[2]}}`, "export").items);
  }
  for (const match of text.matchAll(/\bexport\s+default\s+(?!function\b|class\b|async\b)(\w+)\s*;?/g)) {
    localExports.push({ imported: match[1], name: "default" });
  }
  return { edges, decls, localExports };
}

const mentions = (text: string, names: Set<string>) => [...names].some((name) => new RegExp(`\\b${name}\\b`).test(text));

/** 모듈이 내보내는 모든 이름(값). `export *`는 대상 모듈의 이름을 이어 받는다. */
function exportNames(target: string | null, infos: Map<string, ModuleInfo>, seen = new Set<string>()): Set<string> {
  const names = new Set<string>();
  if (target === null || seen.has(target) || !infos.has(target)) return names;
  seen.add(target);
  const info = infos.get(target)!;
  for (const decl of info.decls) if (decl.exportName !== null) names.add(decl.exportName);
  for (const item of info.localExports) names.add(item.name);
  for (const edge of info.edges) {
    if (edge.kind !== "export" || edge.typeOnly) continue;
    edge.items.forEach((item) => names.add(item.name));
    if (edge.star) exportNames(edge.target, infos, seen).forEach((name) => names.add(name));
  }
  return names;
}

const PROMISE_METHODS = new Set(["then", "catch", "finally"]);

const escapeRe = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * 동적 import 한 곳이 꺼내 쓰는 이름들과, 모듈(또는 그 Promise)을 통째로 넘기는지(whole).
 * - 범위: 그 import를 감싼 최상위 선언(없으면 그 줄). 그 선언이 `const X = import("…")`·`const X = () => import("…")`처럼
 *   import 자체를 이름에 담기만 하면 X를 다른 선언에서 쓰므로 파일 전체를 범위로 본다(검토 plan-1: 지역 Promise).
 * - 출발점: import 식 자신과 모듈(또는 그 Promise)을 담은 이름(위 X, 그 출발점의 `.then((m) =>`의 m, `const m = await 출발점`의 m).
 * - 이름: 출발점에서의 `.이름`·`["이름"]`(대괄호 문자열), 출발점의 `.then(({ 이름 }) =>`, `const { 이름 } = await 출발점`.
 *   범위 안의 다른 값에 대한 접근(`telemetry.send(…)` 등)은 모으지 않는다(결정 #54 2차 S2: 모듈 객체·Promise 접근만 추적).
 * - 모듈을 담은 이름을 속성 접근·호출 없이 그대로 쓰거나(인자로 넘김·대입·반환), 문자열이 아닌 값으로 대괄호 접근하면 whole이다.
 */
function dynamicUsage(text: string, info: ModuleInfo, at: number): { names: Set<string>; whole: boolean } {
  const decl = info.decls.find((d) => d.start <= at && at < d.start + d.text.length);
  const lineStart = text.lastIndexOf("\n", at) + 1;
  const lineEnd = text.indexOf("\n", at);
  const holder = decl?.text.match(
    /^(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(?:(?:async\s*)?\(\s*\)\s*=>\s*)?(?:await\s+)?import\s*\(\s*["'][^"']+["']\s*\)\s*;?\s*$/,
  );
  const raw = holder ? text : (decl?.text ?? text.slice(lineStart, lineEnd === -1 ? undefined : lineEnd));
  const segment = raw.replace(/(["'`])(?:\\.|(?!\1).)*\1/g, '""');
  const names = new Set<string>();
  const addKeys = (list: string) => {
    for (const part of list.split(",")) {
      const key = part.split(/[:=]/)[0].trim();
      if (key !== "") names.add(key);
    }
  };

  // 모듈(또는 그 Promise)을 담은 이름. 출발점 = import 식 또는 그 이름(`X()`처럼 import를 돌려주는 함수 호출 포함).
  const IMPORT = String.raw`\bimport\s*\(\s*""\s*\)`;
  const bindings = new Set<string>(holder ? [holder[1]] : []);
  const origins = () => [IMPORT, ...[...bindings].map((name) => String.raw`\b${escapeRe(name)}\b\s*(?:\(\s*\))?`)].join("|");
  for (let changed = true; changed; ) {
    changed = false;
    const from = origins();
    const found = [
      ...[...segment.matchAll(new RegExp(String.raw`(?:${from})\s*\.\s*then\s*\(\s*(?:async\s+)?(?:\(\s*(\w+)\s*\)|(\w+))\s*=>`, "g"))].map((m) => m[1] ?? m[2]),
      ...[...segment.matchAll(new RegExp(String.raw`\b(?:const|let|var)\s+(\w+)\s*=\s*await\s+(?:${from})`, "g"))].map((m) => m[1]),
    ];
    for (const name of found) {
      if (!bindings.has(name)) {
        bindings.add(name);
        changed = true;
      }
    }
  }
  const from = origins();
  for (const m of segment.matchAll(new RegExp(String.raw`(?:${from})\s*\.\s*then\s*\(\s*(?:async\s+)?\(\s*\{([^}]*)\}\s*\)\s*=>`, "g"))) addKeys(m[1]);
  for (const m of segment.matchAll(new RegExp(String.raw`\b(?:const|let|var)\s+\{([^}]*)\}\s*=\s*await\s+(?:${from})`, "g"))) addKeys(m[1]);
  // 출발점에서의 속성 접근. `(await import("…")).이름`처럼 괄호로 감싼 import 식도 포함한다.
  const accessFrom = [String.raw`${IMPORT}\s*\)?`, ...[...bindings].map((name) => String.raw`\b${escapeRe(name)}\b`)].join("|");
  for (const m of segment.matchAll(new RegExp(String.raw`(?:${accessFrom})\s*(?:\?\.|\.)\s*(\w+)`, "g"))) {
    if (!PROMISE_METHODS.has(m[1])) names.add(m[1]);
  }
  const rawImport = String.raw`\bimport\s*\(\s*["'][^"']+["']\s*\)\s*\)?`;
  const rawAccessFrom = [rawImport, ...[...bindings].map((name) => String.raw`\b${escapeRe(name)}\b`)].join("|");
  for (const m of raw.matchAll(new RegExp(String.raw`(?:${rawAccessFrom})\s*(?:\?\.)?\s*\[\s*(["'\`])(\w+)\1\s*\]`, "g"))) names.add(m[2]);
  let whole = false;
  for (const name of bindings) {
    for (const m of segment.matchAll(new RegExp(`\\b${escapeRe(name)}\\b`, "g"))) {
      const index = m.index ?? 0;
      const before = segment.slice(0, index);
      const after = segment.slice(index + name.length);
      if (/\b(?:const|let|var)\s+$/.test(before)) continue; // 선언 자리
      if (/\bthen\s*\(\s*(?:async\s+)?\(?\s*$/.test(before) && /^\s*\)?\s*=>/.test(after)) continue; // .then((m) => 의 매개변수 자리
      if (/\.\s*$/.test(before)) continue; // 다른 값의 속성 이름(x.m)
      if (/^\s*(?:\?\.|\.|\()/.test(after)) continue; // 속성 접근·호출(Promise의 then, () => import 호출)
      if (/^\s*:(?!:)/.test(after) && /[{,]\s*$/.test(before)) continue; // 객체 키({ m: … })
      if (/\bawait\s+$/.test(before) && /\b(?:const|let|var)\s+(?:\w+|\{[^}]*\})\s*=\s*await\s+$/.test(before)) continue; // const m = await X
      if (/^\s*\[/.test(after)) {
        if (/^\s*\[\s*""\s*\]/.test(after)) continue; // 문자열 대괄호 접근은 위에서 이름으로 모았다
        whole = true; // 계산된 이름으로 꺼냄
        continue;
      }
      whole = true; // 그대로 넘김·대입·반환
    }
  }
  return { names, whole };
}

/**
 * F-3 요청 기능을 이름 단위로 추적한다(결정 4 B2, S2 기준 변경: 실제로 F-3에 도달하는 내보내기·사용만 위반).
 * - F-3 경로 문자열을 가진 최상위 선언, 그리고 F-3 이름을 (값으로) 가져오거나 참조하는 최상위 선언(지역 도우미를 거쳐도)을 F-3 기능으로 본다.
 *   그중 내보내는 이름(`export {}`·`export const 별칭`·`export default`·`export … from`·`export *`)을 그 모듈의 F-3 이름으로 둔다.
 *   같은 모듈에 있어도 F-3에 닿지 않는 선언(출처 컴포넌트 `Citations` 등)은 F-3 이름이 아니다.
 * - 경로 문자열이 어떤 최상위 선언에도 속하지 않으면(최상위 식 등) 그 모듈은 보수적으로 모든 이름을 F-3 기능으로 본다.
 * - 동적 import는 꺼내 쓰는 이름(그 모듈의 내보내기 중)에 F-3 이름이 있거나, 이름을 꺼내지 않고 모듈 전체를 넘기면 F-3 사용이다.
 * - ChatWindow.tsx의 `ChatWindow` 컴포넌트 자신은 허용된 사용 지점이라 F-3 이름이 아니다.
 * 위반: explanation/ 밖 모듈이 F-3 이름을 내보내거나, ChatWindow.tsx가 아닌 모듈이 F-3 이름·F-3 이름을 가진 모듈 전체(네임스페이스·동적 import)를 가져오는 것.
 */
export function analyzeF3(files: Map<string, string>, roots: { src: string; explanation: string; chatWindow: string }) {
  const inside = (file: string) => file.startsWith(roots.explanation + path.sep);
  const infos = new Map([...files].map(([file, text]) => [file, moduleInfo(file, text, files, roots.src)] as const));
  const holders = [...files].filter(([, text]) => text.includes(F3)).map(([file]) => file);
  const conservative = holders.filter((file) => !infos.get(file)!.decls.some((decl) => decl.text.includes(F3)));
  const f3Exports = new Map<string, Set<string>>([...files.keys()].map((file) => [file, new Set<string>()]));
  const f3Locals = new Map<string, Set<string>>([...files.keys()].map((file) => [file, new Set<string>()]));
  const exportsOf = (target: string | null) => (target === null ? new Set<string>() : f3Exports.get(target)!);
  const isF3Item = (item: Item, target: string | null) =>
    item.imported === "*" ? exportsOf(target).size > 0 : exportsOf(target).has(item.imported);
  const dynamicReaches = (file: string, edge: Edge) => {
    const f3 = exportsOf(edge.target);
    if (f3.size === 0) return false;
    const all = exportNames(edge.target, infos);
    const usage = dynamicUsage(files.get(file)!, infos.get(file)!, edge.at);
    const used = [...usage.names].filter((name) => all.has(name));
    return usage.whole || used.length === 0 || used.some((name) => f3.has(name));
  };

  for (let changed = true; changed; ) {
    changed = false;
    for (const [file, info] of infos) {
      const locals = f3Locals.get(file)!;
      const exported = f3Exports.get(file)!;
      const before = locals.size + exported.size;
      const whole = conservative.includes(file);
      for (const edge of info.edges) {
        if (edge.typeOnly) continue;
        if (edge.kind === "import") edge.items.filter((item) => isF3Item(item, edge.target)).forEach((item) => locals.add(item.name));
        if (edge.kind === "export") {
          if (edge.star || whole) exportsOf(edge.target).forEach((name) => exported.add(name));
          edge.items.filter((item) => whole || isF3Item(item, edge.target)).forEach((item) => exported.add(item.name));
        }
      }
      // ChatWindow 컴포넌트 자신은 허용된 사용 지점이다. F-3 기능으로 보지 않는다(그것을 가져오는 화면·재노출은 F-3과 무관).
      const sanctioned = (local: string) => file === roots.chatWindow && local === "ChatWindow";
      for (const decl of info.decls) {
        if (!sanctioned(decl.name) && (whole || decl.text.includes(F3) || mentions(decl.text, locals))) locals.add(decl.name);
      }
      for (const decl of info.decls) {
        if (decl.exportName !== null && locals.has(decl.name) && !sanctioned(decl.name)) exported.add(decl.exportName);
      }
      for (const item of info.localExports) {
        if ((whole || locals.has(item.imported)) && !sanctioned(item.imported)) exported.add(item.name);
      }
      if (locals.size + exported.size !== before) changed = true;
    }
  }

  const violations: string[] = [];
  const rel = (file: string) => path.relative(roots.src, file);
  let chatWindowUses = false;
  for (const [file, info] of infos) {
    if (inside(file)) continue;
    for (const name of f3Exports.get(file)!) violations.push(`${rel(file)}: exports F-3 ${name}`);
    for (const edge of info.edges) {
      if (edge.typeOnly || edge.kind === "export") continue;
      const reaching = edge.kind === "dynamic" ? dynamicReaches(file, edge) : edge.items.some((item) => isF3Item(item, edge.target));
      if (!reaching) continue;
      if (file === roots.chatWindow) chatWindowUses = true;
      else violations.push(`${rel(file)}: ${edge.kind} F-3 from "${edge.specifier}"`);
    }
  }
  return { holders, f3Exports, violations, chatWindowUses };
}

// ---------------------------------------------------------------- F-3을 시작하는 effect 세기(검토 B3)
/**
 * 정밀 도달: F-3 경로 문자열을 가진 최상위 선언에서 시작해, 그 이름을 (가져와) 참조하는 최상위 선언으로만 넓힌다.
 * analyzeF3의 보수적 holder 규칙(holder의 모든 선언)을 쓰지 않는다. 같은 파일의 무관한 effect를 F-3 effect로 세지 않기 위해서다.
 */
export function f3Reach(files: Map<string, string>, srcRoot: string) {
  const infos = new Map([...files].map(([file, text]) => [file, moduleInfo(file, text, files, srcRoot)] as const));
  const locals = new Map<string, Set<string>>([...files.keys()].map((file) => [file, new Set<string>()]));
  const exportsOf = new Map<string, Set<string>>([...files.keys()].map((file) => [file, new Set<string>()]));
  const reachingExports = (target: string | null) => (target === null ? new Set<string>() : exportsOf.get(target)!);
  const reaches = (item: Item, target: string | null) =>
    item.imported === "*" ? reachingExports(target).size > 0 : reachingExports(target).has(item.imported);

  for (let changed = true; changed; ) {
    changed = false;
    for (const [file, info] of infos) {
      const mine = locals.get(file)!;
      const exported = exportsOf.get(file)!;
      const before = mine.size + exported.size;
      for (const edge of info.edges) {
        if (edge.typeOnly) continue;
        if (edge.kind === "import") edge.items.filter((item) => reaches(item, edge.target)).forEach((item) => mine.add(item.name));
        if (edge.kind === "export") {
          if (edge.star) reachingExports(edge.target).forEach((name) => exported.add(name));
          edge.items.filter((item) => reaches(item, edge.target)).forEach((item) => exported.add(item.name));
        }
      }
      for (const decl of info.decls) {
        if (decl.text.includes(F3) || mentions(decl.text, mine)) mine.add(decl.name);
      }
      for (const decl of info.decls) if (decl.exportName !== null && mine.has(decl.name)) exported.add(decl.exportName);
      for (const item of info.localExports) if (mine.has(item.imported)) exported.add(item.name);
      if (mine.size + exported.size !== before) changed = true;
    }
  }
  return { infos, locals };
}

const EFFECT_CALL = /\buse(?:Layout|Insertion)?Effect\s*\(/g;

/** text[open]이 "("일 때 짝이 맞는 ")"까지의 호출 인자 텍스트. */
function callArgs(text: string, open: number): string {
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === "(") depth += 1;
    else if (text[i] === ")") {
      depth -= 1;
      if (depth === 0) return text.slice(open, i + 1);
    }
  }
  return text.slice(open);
}

/**
 * 앱 코드에서 F-3을 시작하는 effect(useEffect·useLayoutEffect·useInsertionEffect 호출)를 모두 찾는다.
 * effect 인자 텍스트가 F-3 경로 문자열을 갖거나, 도달 이름(그 파일의 최상위 도달 이름 + 감싼 최상위 선언 안의 지역 선언 중
 * 도달 이름을 참조하는 것, 반복)을 참조하면 F-3을 시작하는 effect로 센다. 같은 요청 함수를 두 effect에서 부르면 둘로 센다.
 */
export function f3Effects(files: Map<string, string>, srcRoot: string): Array<{ file: string; text: string }> {
  const { infos, locals } = f3Reach(files, srcRoot);
  const found: Array<{ file: string; text: string }> = [];
  for (const [file, info] of infos) {
    // react에서 별칭으로 가져온 effect(`useEffect as useRunEffect`)도 effect 호출로 센다(검토 plan-2).
    const aliases = info.edges
      .filter((edge) => edge.kind === "import" && !edge.typeOnly && edge.specifier === "react")
      .flatMap((edge) => edge.items)
      .filter((item) => /^use(?:Layout|Insertion)?Effect$/.test(item.imported) && item.name !== item.imported)
      .map((item) => escapeRe(item.name));
    const effectCall = new RegExp(aliases.length === 0 ? EFFECT_CALL.source : `${EFFECT_CALL.source}|\\b(?:${aliases.join("|")})\\s*\\(`, "g");
    for (const decl of info.decls) {
      if (!new RegExp(effectCall.source).test(decl.text)) continue;
      const names = new Set(locals.get(file)!);
      const inner = [...decl.text.matchAll(/\b(?:const|let|var|function\*?)\s+(\w+)/g)]
        .filter((m) => m[1] !== decl.name)
        .map((m) => {
          const index = m.index ?? 0;
          const block = /^function/.test(m[0]);
          return { name: m[1], text: statementAt(decl.text, index, block) };
        });
      for (let changed = true; changed; ) {
        changed = false;
        for (const local of inner) {
          if (!names.has(local.name) && (local.text.includes(F3) || mentions(local.text, names))) {
            names.add(local.name);
            changed = true;
          }
        }
      }
      for (const match of decl.text.matchAll(effectCall)) {
        const open = (match.index ?? 0) + match[0].length - 1;
        const args = callArgs(decl.text, open);
        if (args.includes(F3) || mentions(args, names)) found.push({ file, text: args });
      }
    }
  }
  return found;
}

// ---------------------------------------------------------------- 실제 코드 검사
describe("AC-7 F-3 호출 지점은 대화창 effect 하나 (C12b-3a, H-11c, 결정 3 T-4, 결정 4 B2)", () => {
  it("F-3 경로 문자열은 앱 코드 전체에서 features/chat/explanation/ 아래 한 파일에 한 번만 있다(#51 되돌리기·#53 적용·결과 화면 코드에는 없다)", () => {
    const counts = [...appSources()]
      .map(([file, text]) => ({ file, count: text.split(F3).length - 1 }))
      .filter((entry) => entry.count > 0);
    expect(counts.map((c) => path.relative(SRC, c.file))).toHaveLength(1);
    expect(counts[0].count).toBe(1);
    expect(counts[0].file.startsWith(EXPLANATION + path.sep)).toBe(true);
  });

  it("F-3 요청 기능(이름)은 ChatWindow.tsx만 가져다 쓴다: features/chat/index.ts 재노출, ChatWindow의 별칭 재내보내기, 다른 화면의 가져오기, 동적 import 우회가 없다", () => {
    const result = analyzeF3(appSources(), { src: SRC, explanation: EXPLANATION, chatWindow: CHAT_WINDOW });
    expect(result.holders).toHaveLength(1);
    expect(result.violations).toEqual([]);
    expect(result.chatWindowUses).toBe(true); // effect는 대화창에 장착된다(결정 U-2)
  });

  it("F-3을 시작하는 effect는 앱 코드 전체에서 하나이고, ChatWindow.tsx 또는 그것이 쓰는 explanation/ 코드 안에 있다(같은 요청 함수를 두 effect에서 시작하지 않음)", () => {
    const effects = f3Effects(appSources(), SRC);
    expect(effects.map((e) => path.relative(SRC, e.file))).toHaveLength(1);
    expect(effects[0].file === CHAT_WINDOW || effects[0].file.startsWith(EXPLANATION + path.sep)).toBe(true);
  });
});

describe("AC-12 GET /api/plan을 직접 부르지 않고 store를 구독한다 (C12a-6-5, J-12f)", () => {
  it("explanation/ 코드가 있고, 거기와 ChatWindow.tsx에는 /api/plan 경로·fetch 직접 호출이 없으며, 재조회는 store의 loadPlan()으로 한다", () => {
    const files = [...appSources()].filter(([file]) => file.startsWith(EXPLANATION + path.sep) || file === CHAT_WINDOW);
    const explanationFiles = files.filter(([file]) => file !== CHAT_WINDOW);
    expect(explanationFiles.length).toBeGreaterThan(0);
    for (const [file, text] of files) {
      const name = path.relative(SRC, file);
      expect(text, name).not.toContain("/api/plan");
      expect(text, name).not.toMatch(/\bfetch\s*\(|XMLHttpRequest/);
    }
    expect(explanationFiles.some(([, text]) => /\.loadPlan\s*\(/.test(text))).toBe(true);
  });
});

// ---------------------------------------------------------------- 검사기 자체 확인(보조)
describe("보조: 정적 검사기가 우회를 잡는다", () => {
  const root = path.join(path.sep, "fx", "src");
  const p = (...parts: string[]) => path.join(root, ...parts);
  const roots = { src: root, explanation: p("features", "chat", "explanation"), chatWindow: p("features", "chat", "ChatWindow.tsx") };
  const CHAT_INDEX = p("features", "chat", "index.ts");
  const CHAT_WINDOW_FX = p("features", "chat", "ChatWindow.tsx");
  const RESULT = p("features", "result", "ResultPage.tsx");
  const base = (): Array<[string, string]> => [
    [p("features", "chat", "explanation", "request.ts"), `export function send(api) { return api.request("POST", "${F3}", {}); }`],
    [p("features", "chat", "explanation", "useExplanation.ts"), `import { send } from "./request";\nexport function useExplanation() { send(); }`],
    // 혼합 배럴: F-3 요청 기능(useExplanation)과 무관한 이름(Citations)을 함께 내보낸다.
    [p("features", "chat", "explanation", "index.ts"), `export { useExplanation } from "./useExplanation";\nexport { Citations } from "./Citations";`],
    [p("features", "chat", "explanation", "Citations.tsx"), `export function Citations() { return null; }`],
    [CHAT_WINDOW_FX, `import { useExplanation } from "./explanation/useExplanation";\nimport { Citations } from "./explanation/Citations";\nexport function ChatWindow() { useExplanation(); }`],
    [CHAT_INDEX, `export { ChatWindow } from "./ChatWindow";`],
    [RESULT, `import { ChatWindow } from "../chat";\nimport { Citations } from "../chat/explanation/Citations";\nconst Lazy = () => import("../chat");`],
  ];
  const run = (extra: Array<[string, string]> = [], replace: Record<string, string> = {}) => {
    const files = new Map(base());
    for (const [file, text] of Object.entries(replace)) files.set(file, text);
    for (const [file, text] of extra) files.set(file, text);
    return analyzeF3(files, roots);
  };
  const violationsIn = (result: ReturnType<typeof run>, file: string) =>
    result.violations.filter((v) => v.startsWith(path.relative(root, file) + ":"));

  it("정상 구조는 통과한다(F-3과 무관한 이름·ChatWindow 지연 로딩은 막지 않음)", () => {
    const result = run();
    expect(result.violations).toEqual([]);
    expect(result.chatWindowUses).toBe(true);
  });

  it("혼합 배럴에서 F-3과 무관한 이름(Citations)만 가져오거나 재노출하면 막지 않는다 (결정 4 B2)", () => {
    for (const [file, text] of [
      [RESULT, `import { ChatWindow } from "../chat";\nimport { Citations } from "../chat/explanation";`],
      [RESULT, `import { Citations as Sources, type Unused } from "../chat/explanation";`],
      [CHAT_INDEX, `export { ChatWindow } from "./ChatWindow";\nexport { Citations } from "./explanation";`],
      [CHAT_INDEX, `import { Citations } from "./explanation";\nexport { ChatWindow } from "./ChatWindow";\nexport { Citations };`],
    ] as const) {
      const result = run([], { [file]: text });
      expect(result.violations, text).toEqual([]);
      expect(result.chatWindowUses, text).toBe(true);
    }
  });

  it("F-3 경로 선언과 같은 모듈의 출처 컴포넌트(Citations)만 가져오거나, 혼합 배럴을 동적으로 읽어 Citations만 쓰면 막지 않는다 (S2 기준 변경, 검토 plan-1)", () => {
    const REQUEST_FX = p("features", "chat", "explanation", "request.ts");
    const mixedHolder = `export function send(api) { return api.request("POST", "${F3}", {}); }\nexport function Citations() { return null; }`;
    for (const text of [
      `import { Citations } from "../chat/explanation/request";`,
      `import { lazy } from "react";\nconst Sources = lazy(() => import("../chat/explanation").then((m) => ({ default: m.Citations })));`,
      `export async function load() {\n  const { Citations } = await import("../chat/explanation");\n  return Citations;\n}`,
      `const Sources = import("../chat/explanation").then(({ Citations }) => Citations);`,
      // 검토 plan-1(2회차): 동적 import의 Promise를 지역 이름에 두고 다음 선언에서 Citations만 꺼내 쓴다.
      `import { lazy } from "react";\nconst mod = import("../chat/explanation");\nexport const Sources = lazy(() => mod.then((m) => ({ default: m.Citations })));`,
      `const load = () => import("../chat/explanation");\nexport async function sources() {\n  const m = await load();\n  return m["Citations"];\n}`,
      `const mod = import("../chat/explanation");\nexport async function sources() {\n  const m = await mod;\n  return m.Citations;\n}`,
      // 결정(#54 2차 S2): 동적 import의 모듈 객체·Promise 접근만 추적한다. 무관한 객체의 send 호출은 F-3 사용이 아니다.
      `import { lazy } from "react";\nconst mod = import("../chat/explanation/request");\nexport const Sources = lazy(() => mod.then((m) => ({ default: m.Citations })));\nexport function track() {\n  telemetry.send("results");\n}`,
      `const mod = import("../chat/explanation/request");\nexport const Sources = mod.then((m) => m.Citations);\nexport function track() {\n  telemetry.send("results");\n}`,
      `export async function load() {\n  const { Citations } = await import("../chat/explanation/request");\n  telemetry.send("results");\n  return Citations;\n}`,
      `const Sources = import("../chat/explanation/request").then((m) => {\n  telemetry.send("loaded");\n  return m.Citations;\n});`,
      `export async function load() {\n  return (await import("../chat/explanation/request")).Citations;\n}`,
    ]) {
      const result = run([], { [REQUEST_FX]: mixedHolder, [RESULT]: text });
      expect(result.violations, text).toEqual([]);
      expect(result.chatWindowUses, text).toBe(true);
    }
    // 대조: 같은 경로로 F-3 이름을 꺼내거나 모듈 전체를 넘기면 여전히 잡는다.
    for (const text of [
      `import { send } from "../chat/explanation/request";`,
      `import { Citations, send } from "../chat/explanation/request";`,
      `const ask = import("../chat/explanation").then((m) => m.useExplanation);`,
      `const ask = import("../chat/explanation").then(({ useExplanation }) => useExplanation);`,
      `export async function load() {\n  const { useExplanation } = await import("../chat/explanation");\n  return useExplanation;\n}`,
      `const all = import("../chat/explanation").then((m) => register(m));`,
      `const m = () => import("../chat/explanation/request");`,
      // 검토 plan-1(2회차): Citations와 함께 F-3 이름을 대괄호로 꺼내거나, 모듈·Promise를 통째로 넘긴다.
      `const pair = import("../chat/explanation/request").then((m) => [m.Citations, m["send"]]);`,
      `const pair = import("../chat/explanation").then((m) => ({ default: m.Citations, ask: m["useExplanation"] }));`,
      `const pick = (key) => import("../chat/explanation").then((m) => ({ default: m.Citations, other: m[key] }));`,
      `const Sources = import("../chat/explanation").then((m) => {\n  register(m);\n  return m.Citations;\n});`,
      `const mod = import("../chat/explanation");\nexport const Sources = mod.then((m) => m.Citations);\nregister(mod);`,
      `const mod = import("../chat/explanation");\nexport const ask = mod.then((m) => m.useExplanation);`,
      `const mod = import("../chat/explanation");\nexport async function sources() {\n  const m = await mod;\n  keep(m);\n  return m.Citations;\n}`,
      // 결정(#54 2차 S2): 무관한 telemetry.send가 같이 있어도, 같은 모듈 객체의 대괄호 접근·모듈 전체 전달·직접 접근은 그대로 잡는다.
      `const mod = import("../chat/explanation/request");\nexport const pair = mod.then((m) => [m.Citations, m["send"]]);\nexport function track() {\n  telemetry.send("results");\n}`,
      `const mod = import("../chat/explanation/request");\nexport const Sources = mod.then((m) => m.Citations);\nexport function track() {\n  telemetry.send("results");\n  register(mod);\n}`,
      `export async function load() {\n  telemetry.send("results");\n  return (await import("../chat/explanation/request")).send;\n}`,
      `export async function load() {\n  const m = await import("../chat/explanation/request");\n  telemetry.send("results");\n  return m?.["send"];\n}`,
    ]) {
      expect(violationsIn(run([], { [REQUEST_FX]: mixedHolder, [RESULT]: text }), RESULT), text).not.toEqual([]);
    }
  });

  it("같은 혼합 배럴에서 F-3 요청 기능을 가져오거나(별칭·네임스페이스·동적 import) 재노출하면 잡는다", () => {
    for (const [file, text] of [
      [RESULT, `import { Citations, useExplanation } from "../chat/explanation";`],
      [RESULT, `import { useExplanation as ask } from "../chat/explanation";`],
      [RESULT, `import * as explanation from "../chat/explanation";`],
      [RESULT, `const m = () => import("../chat/explanation");`],
      [CHAT_INDEX, `export { ChatWindow } from "./ChatWindow";\nexport { Citations, useExplanation } from "./explanation";`],
      [CHAT_INDEX, `export { ChatWindow } from "./ChatWindow";\nexport * from "./explanation";`],
      [CHAT_INDEX, `export { ChatWindow } from "./ChatWindow";\nexport * as explanation from "./explanation";`],
      [CHAT_INDEX, `import { useExplanation } from "./explanation";\nexport { ChatWindow } from "./ChatWindow";\nexport { useExplanation as ask };`],
    ] as const) {
      expect(violationsIn(run([], { [file]: text }), file), text).not.toEqual([]);
    }
  });

  it("features/chat/index.ts 재노출(직접·배럴·export *)을 잡는다", () => {
    for (const text of [
      `export { useExplanation } from "./explanation/useExplanation";`,
      `export { useExplanation } from "./explanation";`,
      `export * from "./explanation/request";`,
    ]) {
      expect(run([], { [CHAT_INDEX]: text }).violations.length, text).toBeGreaterThan(0);
    }
  });

  it("다른 화면(#51·#53)의 가져오기·동적 import와 ChatWindow의 재내보내기를 잡는다", () => {
    expect(run([[p("features", "result", "UndoToast.tsx"), `import { send } from "../chat/explanation/request";`]]).violations).not.toEqual([]);
    expect(run([[p("features", "chat", "apply", "ApplyButton.tsx"), `import { useExplanation } from "../explanation";`]]).violations).not.toEqual([]);
    expect(run([[p("features", "result", "X.tsx"), `const m = import("../chat/explanation/useExplanation");`]]).violations).not.toEqual([]);
    const reexport = `import { useExplanation } from "./explanation/useExplanation";\nexport function ChatWindow() { useExplanation(); }\nexport { useExplanation };`;
    expect(run([], { [CHAT_WINDOW_FX]: reexport }).violations).not.toEqual([]);
  });

  it("ChatWindow가 가져온 F-3 기능을 다른 이름(별칭·감싼 함수·default)으로 내보내고 chat/index.ts가 전달하면 두 곳 모두 잡는다", () => {
    const head = `import { useExplanation } from "./explanation/useExplanation";\nexport function ChatWindow() { useExplanation(); }\n`;
    for (const [tail, exportedName] of [
      [`export const requestExplanation = useExplanation;`, "requestExplanation"],
      [`export const requestExplanation = () => useExplanation();`, "requestExplanation"],
      [`export function requestExplanation() {\n  return useExplanation();\n}`, "requestExplanation"],
      [`export { useExplanation as requestExplanation };`, "requestExplanation"],
      [`export default useExplanation;`, "default"],
    ] as const) {
      const index =
        exportedName === "default"
          ? `export { ChatWindow, default as requestExplanation } from "./ChatWindow";`
          : `export { ChatWindow, ${exportedName} } from "./ChatWindow";`;
      const result = run([], { [CHAT_WINDOW_FX]: head + tail, [CHAT_INDEX]: index });
      expect(violationsIn(result, CHAT_WINDOW_FX), tail).not.toEqual([]);
      expect(violationsIn(result, CHAT_INDEX), tail).not.toEqual([]);
    }
    // 대조: ChatWindow 컴포넌트만 내보내고 index가 그것을 전달하는 것은 정상이다(위 정상 구조와 같음).
    const plain = run([], { [CHAT_WINDOW_FX]: head, [CHAT_INDEX]: `export { ChatWindow } from "./ChatWindow";` });
    expect(plain.violations).toEqual([]);
    expect(plain.chatWindowUses).toBe(true);
  });

  it("explanation/ 안에서 지역 도우미를 거쳐 F-3을 부르는 이름도 F-3 요청 기능으로 본다", () => {
    const indirect = `import { send } from "./request";\nfunction go() {\n  send();\n}\nexport function useExplanation() {\n  go();\n}\nexport function unrelated() {\n  return 1;\n}`;
    const replace = { [p("features", "chat", "explanation", "useExplanation.ts")]: indirect };
    expect(run([], replace).violations).toEqual([]);
    const reach = run([[p("features", "result", "Y.tsx"), `import { useExplanation } from "../chat/explanation/useExplanation";`]], replace);
    expect(reach.violations).not.toEqual([]);
    const unrelated = run([[p("features", "result", "Y.tsx"), `import { unrelated } from "../chat/explanation/useExplanation";`]], replace);
    expect(unrelated.violations).toEqual([]);
  });
});

describe("보조: F-3 effect 수 검사기가 두 effect를 잡는다 (검토 B3)", () => {
  const root = path.join(path.sep, "fx", "src");
  const p = (...parts: string[]) => path.join(root, ...parts);
  const REQUEST = p("features", "chat", "explanation", "request.ts");
  const HOOK = p("features", "chat", "explanation", "useExplanation.ts");
  const CHAT_WINDOW_FX = p("features", "chat", "ChatWindow.tsx");
  const request = `export function send(api, id) {\n  return api.request("POST", "${F3}", { calcRunId: id });\n}`;
  const chatWindow = [
    `import { useEffect, useRef } from "react";`,
    `import { useExplanation } from "./explanation/useExplanation";`,
    `export function ChatWindow({ deps }) {`,
    `  const mountedRef = useRef(false);`,
    `  useEffect(() => {`,
    `    mountedRef.current = true;`,
    `    return () => {`,
    `      mountedRef.current = false;`,
    `    };`,
    `  }, []);`,
    `  useExplanation(deps);`,
    `  return null;`,
    `}`,
  ].join("\n");
  const hook = (body: string) =>
    [
      `import { useCallback, useEffect, useRef } from "react";`,
      `import { send } from "./request";`,
      `const sentIds = new Set();`,
      `export function forget() {\n  sentIds.clear();\n}`,
      `export function useExplanation(deps) {`,
      `  const lastRef = useRef(null);`,
      `  const start = useCallback((id) => {`,
      `    lastRef.current = id;`,
      `    send(deps.api, id);`,
      `  }, [deps]);`,
      body,
      `}`,
    ].join("\n");
  const count = (hookText: string, windowText = chatWindow, extra: Array<[string, string]> = []) =>
    f3Effects(new Map<string, string>([[REQUEST, request], [HOOK, hookText], [CHAT_WINDOW_FX, windowText], ...extra]), root).length;

  const changeEffect = `  useEffect(() => {\n    const id = deps.planStore.getState().view?.calcRunId;\n    if (id != null) start(id);\n  }, [deps, start]);`;
  const clearEffect = `  useEffect(() => deps.planStore.subscribe(() => {\n    lastRef.current = null;\n    forget();\n  }), [deps]);`;

  it("effect 하나가 지역 도우미(useCallback)를 거쳐 F-3을 시작하면 1이고, F-3을 시작하지 않는 다른 effect(clear 구독·mountedRef)는 세지 않는다", () => {
    expect(count(hook(changeEffect + "\n" + clearEffect))).toBe(1);
  });

  it("최초 조회용 effect와 결과 변경용 effect가 같은 요청 함수를 각각 시작하면 2로 센다", () => {
    const initialEffect = `  useEffect(() => {\n    const id = deps.planStore.getState().view?.calcRunId;\n    if (id != null) start(id);\n  }, []);`;
    expect(count(hook(initialEffect + "\n" + changeEffect + "\n" + clearEffect))).toBe(2);
  });

  it("react에서 별칭으로 가져온 effect가 같은 요청 함수를 시작해도 따로 세어 2로 센다 (검토 plan-2)", () => {
    const initialEffect = `  useEffect(() => {\n    const id = deps.planStore.getState().view?.calcRunId;\n    if (id != null) start(id);\n  }, []);`;
    const aliasEffect = changeEffect.replace("useEffect(", "useRunEffect(");
    const aliased = hook(initialEffect + "\n" + aliasEffect + "\n" + clearEffect).replace(
      `import { useCallback, useEffect, useRef } from "react";`,
      `import { useCallback, useEffect, useEffect as useRunEffect, useRef } from "react";`,
    );
    expect(count(aliased)).toBe(2);
    // 대조: 별칭 effect 하나만 F-3을 시작하면 1이다.
    const single = hook(aliasEffect + "\n" + clearEffect).replace(
      `import { useCallback, useEffect, useRef } from "react";`,
      `import { useCallback, useEffect, useEffect as useRunEffect, useRef } from "react";`,
    );
    expect(count(single)).toBe(1);
  });

  it("훅의 effect와 ChatWindow의 effect가 같은 요청 기능을 각각 시작하면 2로 센다", () => {
    const window = chatWindow.replace(
      "  useExplanation(deps);",
      `  const ask = useExplanation(deps);\n  useEffect(() => {\n    ask();\n  }, [ask]);`,
    );
    expect(count(hook(changeEffect + "\n  return start;"), window)).toBe(2);
  });

  it("경로 문자열을 effect 안에서 직접 쓰거나, effect가 만든 제어기가 F-3을 보내도 F-3 effect로 센다", () => {
    const direct = hook(`  useEffect(() => {\n    deps.api.request("POST", "${F3}", {});\n  }, [deps]);`);
    expect(count(direct)).toBe(1);
    const CONTROLLER = p("features", "chat", "explanation", "controller.ts");
    const controller = `import { send } from "./request";\nexport function createController(deps) {\n  return deps.planStore.subscribe(() => send(deps.api, 1));\n}`;
    const viaController = hook(`  useEffect(() => createController(deps), [deps]);`).replace(
      `import { send } from "./request";`,
      `import { createController } from "./controller";`,
    );
    expect(count(viaController, chatWindow, [[CONTROLLER, controller]])).toBe(1);
  });
});
