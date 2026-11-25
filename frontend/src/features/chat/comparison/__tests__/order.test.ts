// spec.md "제약"의 구현 단계 TODO(AC-15): 되돌리기 토스트 표시 요청도 ①→②→③ 순서 기록에 넣는다.
import { describe, expect, it } from "vitest";
import { doneMsg } from "../../__tests__/helpers";
import { createComparisonController } from "../controller";
import { comparison, makeApi, makeStore } from "./helpers";

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe("AC-15 ①→②→③ 순서 기록에 되돌리기 토스트 표시 요청을 넣는다", () => {
  it("store revision 13 → 되돌리기 토스트(undoRevision 13) → loadPlan 시작 순서다", async () => {
    const api = makeApi({
      "F4 1031": [comparison("get-comparison.200.json", { messageId: 1031 })],
      "F5 1031": [{ revision: 13 }],
    });
    const { store } = makeStore();
    const events: string[] = [];
    const loadPlan = store.loadPlan.bind(store);
    store.loadPlan = ((...args: Parameters<typeof loadPlan>) => {
      events.push("loadPlan");
      return loadPlan(...args);
    }) as typeof store.loadPlan;
    store.subscribe((state) => {
      if (state.revision === 13 && !events.includes("revision 13")) events.push("revision 13");
    });

    const controller = createComparisonController({ api, planStore: store });
    controller.listen(() => {
      if (controller.getSnapshot().undoRevision === 13 && !events.includes("toast")) events.push("toast");
    });
    controller.sync([doneMsg(1031, "RECALC_RESULT", "월 저축액을 약 50만원으로 바꾸면 평균일 때 달성해요")]);
    await flush();
    controller.apply(1031);
    await flush();
    await flush();

    expect(events).toEqual(["revision 13", "toast", "loadPlan"]);
  });
});
