import { describe, expect, it } from "vitest";
import { chartLayout } from "../layout";

describe("그래프 크기 chartLayout (#298)", () => {
  it("넓은 화면에서는 세로 420px로 크게 그린다", () => {
    expect(chartLayout(640).height).toBe(420);
  });

  it("좁은 화면(480px 미만)에서도 세로 300px 이상이다", () => {
    expect(chartLayout(360).height).toBe(300);
    expect(chartLayout(479).height).toBe(300);
    expect(chartLayout(480).height).toBe(420);
  });

  it("compact(기존 길이)면 넓은 화면 300px, 좁은 화면 240px이다(비교용)", () => {
    expect(chartLayout(640, true).height).toBe(300);
    expect(chartLayout(360, true).height).toBe(240);
  });

  it("그려지는 세로 길이는 높이에서 위아래 여백을 뺀 값이다", () => {
    const layout = chartLayout(640);

    expect(layout.plotH).toBe(layout.height - layout.padTop - layout.padBottom);
    expect(layout.plotW).toBe(layout.width - layout.padLeft - layout.padRight);
  });
});
