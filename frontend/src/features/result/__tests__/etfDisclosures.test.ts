// Issue #374: 결과 화면의 공시 링크는 적재를 허락받은 자료(scripts/ingest/allowlist.yml)만 가리킨다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { ETF_DISCLOSURES } from "../etfDisclosures";

const here = path.dirname(fileURLToPath(import.meta.url));
const allowlist = fs.readFileSync(path.resolve(here, "../../../../../scripts/ingest/allowlist.yml"), "utf8");
const allowedUrls = [...allowlist.matchAll(/^\s*- url:\s*(\S+)/gm)].map((m) => m[1]);

describe("ETF 공시 목록 (#374)", () => {
  const entries = Object.entries(ETF_DISCLOSURES).flatMap(([code, docs]) => docs.map((doc) => ({ code, ...doc })));

  it.each(entries)("$code $title 의 URL은 allowlist.yml에 있다", ({ sourceUrl }) => {
    expect(allowedUrls).toContain(sourceUrl);
  });

  it("대화로 바꿀 수 있는 세 종목(SP500, 069500, 133690)이 모두 있다", () => {
    expect(Object.keys(ETF_DISCLOSURES).sort()).toEqual(["069500", "133690", "SP500"]);
  });

  it("S&P500은 서로 다른 운용사 2곳 이상이다(FR-AI-03)", () => {
    const orgs = new Set(ETF_DISCLOSURES.SP500.map((doc) => doc.orgName));
    expect(orgs.size).toBeGreaterThanOrEqual(2);
  });
});
