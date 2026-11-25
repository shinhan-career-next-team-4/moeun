// AC-5(화면 코드 규칙), AC-8(Vite 프록시·README), AC-21(프로젝트 구성·README): 프로젝트 골격 확인.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const here = path.dirname(fileURLToPath(import.meta.url));
const frontend = path.resolve(here, "../../..");
const src = path.join(frontend, "src");
const read = (rel: string) => fs.readFileSync(path.join(frontend, rel), "utf8");

function walk(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
}

describe("AC-21 프로젝트가 올라갔다", () => {
  it("frontend/.gitkeep 대신 실제 프로젝트가 있다", () => {
    expect(fs.existsSync(path.join(frontend, ".gitkeep"))).toBe(false);
    for (const f of ["package.json", "index.html", "vite.config.ts", "README.md", "src/app/App.tsx", "src/app/main.tsx"]) {
      expect(fs.existsSync(path.join(frontend, f)), f).toBe(true);
    }
  });

  it("package.json에 dev·build·lint·test 스크립트가 있다", () => {
    const pkg = JSON.parse(read("package.json"));
    for (const name of ["dev", "build", "lint", "test"]) {
      expect(typeof pkg.scripts?.[name], `scripts.${name}`).toBe("string");
    }
    const deps = { ...pkg.dependencies, ...pkg.devDependencies };
    for (const name of ["react", "react-dom", "react-router-dom", "vite", "typescript", "vitest"]) {
      expect(deps[name], name).toBeTruthy();
    }
  });

  it("index.html은 #root와 src/app/main.tsx 진입점을 가진다", () => {
    const html = read("index.html");
    expect(html).toMatch(/id=["']root["']/);
    expect(html).toContain("/src/app/main.tsx");
  });

  it("README에 빌드·린트·테스트 명령이 적혀 있다", () => {
    const readme = read("README.md");
    expect(readme).toContain("npm run build");
    expect(readme).toContain("npm run lint");
    expect(readme).toMatch(/npm (run )?test/);
  });

  it("README에 목 선택 방식(useMock)과 목 모드 전환 방법이 적혀 있다", () => {
    const readme = read("README.md");
    expect(readme).toContain("useMock");
    expect(readme).toContain("VITE_API_MODE=mock");
    expect(readme).toContain("메서드 + 경로");
  });

  it("README에 공유 계획 store 사용법이 적혀 있다: loadPlan()만 GET /api/plan을 부른다", () => {
    const readme = read("README.md");
    expect(readme).toContain("loadPlan()");
    expect(readme).toMatch(/GET \/api\/plan/);
    expect(readme).toContain("settledStatus");
    expect(readme).toContain("acceptNoPlan");
  });
});

describe("AC-8 같은 사이트 전제", () => {
  it("개발 서버가 /api를 Spring으로 프록시한다", async () => {
    const mod = await import("../../../vite.config");
    const raw = mod.default as unknown;
    const config: any =
      typeof raw === "function"
        ? await (raw as (env: unknown) => unknown)({ command: "serve", mode: "development", isSsrBuild: false, isPreview: false })
        : raw;
    const proxy = config.server?.proxy;
    expect(proxy, "server.proxy").toBeTruthy();
    const entry = proxy["/api"];
    expect(entry, 'server.proxy["/api"]').toBeTruthy();
    const target = typeof entry === "string" ? entry : entry.target;
    expect(String(target)).toMatch(/^https?:\/\//);
  });

  it("README에 SameSite=Lax 전제와 Vite 프록시 설정이 적혀 있다", () => {
    const readme = read("README.md");
    expect(readme).toContain("SameSite=Lax");
    expect(readme).toContain("같은 사이트");
    expect(readme).toMatch(/프록시/);
    expect(readme).toContain("/api");
    expect(readme).toContain("vite.config.ts");
    expect(readme).toContain("credentials");
  });
});

describe("AC-5·AC-16 화면 코드는 fetch·목 함수를 직접 다루지 않는다", () => {
  const screenFiles = walk(src).filter((f) => {
    const rel = path.relative(src, f).split(path.sep);
    if (!/\.(ts|tsx)$/.test(f)) return false;
    if (/\.test\.(ts|tsx)$/.test(f)) return false;
    return !["api", "mocks", "__tests__"].includes(rel[0]) && !rel.includes("__tests__");
  });

  it("src/api·src/mocks 밖(app 등)에는 검사할 파일이 있다", () => {
    expect(screenFiles.length).toBeGreaterThan(0);
  });

  it.each(["\\bfetch\\s*\\(", "useMock", "mockFetch", "XMLHttpRequest"])("%s 를 쓰지 않는다", (pattern) => {
    const offenders = screenFiles.filter((f) => new RegExp(pattern).test(fs.readFileSync(f, "utf8")));
    expect(offenders.map((f) => path.relative(frontend, f))).toEqual([]);
  });
});
