import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { COPY } from "../copy";

const here = path.dirname(fileURLToPath(import.meta.url));
const ymlPath = path.resolve(here, "../../../../../copy/copy.ko.yml");

function flattenYamlStrings(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  const stack: { indent: number; key: string }[] = [];
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.replace(/#.*$/, "");
    if (!line.trim()) continue;
    const indent = rawLine.search(/\S/);
    const match = /^\s*("?[^":]+"?):\s*(.*)$/.exec(rawLine);
    if (!match) continue;
    const key = match[1].replace(/^"|"$/g, "");
    const rawValue = match[2].trim();
    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) {
      stack.pop();
    }
    if (rawValue === "" || rawValue.startsWith("#")) {
      stack.push({ indent, key });
      continue;
    }
    const quoted = /^"(.*)"(?:\s*#.*)?$/.exec(rawValue);
    if (quoted) {
      const fullKey = [...stack.map((s) => s.key), key].join(".");
      result[fullKey] = quoted[1];
    }
  }
  return result;
}

describe("COPY는 copy/copy.ko.yml과 일치한다", () => {
  const yml = flattenYamlStrings(fs.readFileSync(ymlPath, "utf8"));

  it.each(Object.entries(COPY))("%s 키가 copy/copy.ko.yml과 같다", (key, value) => {
    expect(yml[key]).toBe(value);
  });
});
