// 라운드 2 리뷰 지적(B1·B2·B3): 잠긴 수용 테스트가 다루지 않는 경계. 제어기·파서를 직접 확인한다.
import { describe, expect, it } from "vitest";
import { createPlanStore } from "../../../../api/planStore";
import { createComparisonController } from "../controller";
import { parseComparison } from "../types";
import { doneMsg } from "../../__tests__/helpers";
import { comparison, deferred, planView } from "./helpers";

describe("B1 같은 시나리오가 두 번 든 F-4 200은 P-59대로 카드를 그리지 않는다", () => {
  it("정상 본문은 파싱되고, BAD 항목을 AVG 복제본으로 바꾸면 null이다", () => {
    const body = comparison("get-comparison.200.json") as any;
    expect(parseComparison(body)).not.toBeNull();
    const list = body.diff.byScenario as Array<{ scenario: string }>;
    const bad = list.findIndex((item) => item.scenario === "BAD");
    const avg = list.findIndex((item) => item.scenario === "AVG");
    expect(bad).toBeGreaterThan(-1);
    list[bad] = structuredClone(list[avg]);
    expect(parseComparison(body)).toBeNull();
  });
});

const recalc = (id: number) => doneMsg(id, "RECALC_RESULT", "비교 결과");

function setup(applyReply: Promise<unknown>) {
  const planGets = { count: 0 };
  const store = createPlanStore({
    api: {
      request: async () => {
        planGets.count += 1;
        return planView(1);
      },
    },
    navigate: () => {},
  });
  store.acceptPlanView(planView(12));
  const api = {
    request: async (method: string, path: string) => {
      if (method === "GET") return comparison("get-comparison.200.json", { messageId: 1031, applicable: true });
      if (path.endsWith("/apply")) return applyReply;
      throw new Error(path);
    },
  };
  const controller = createComparisonController({ api, planStore: store } as any);
  return { store, controller, planGets };
}

const settle = async () => {
  for (let i = 0; i < 10; i += 1) await Promise.resolve();
};

describe("B3 대화창 구독이 끊긴 사이 store가 비워졌다 새 계획으로 채워지면 이전 적용 성공을 무시한다", () => {
  it("구독 해제 뒤 acceptNoPlan·새 계획 로드가 일어나도 이전 F-5 revision 13을 반영하지 않는다", async () => {
    const reply = deferred<unknown>();
    const { store, controller, planGets } = setup(reply.promise);
    const unlisten = controller.listen(() => {});
    controller.sync([recalc(1031)]);
    await settle();
    controller.apply(1031);
    await settle();
    unlisten(); // 대화창 언마운트
    store.acceptNoPlan(12);
    store.acceptPlanView(planView(1));
    reply.resolve({ revision: 13 });
    await settle();
    expect(store.getState().revision).toBe(1);
    expect(store.getState().view?.revision).toBe(1);
    expect(planGets.count).toBe(0); // 이전 응답에 따른 재조회도 없다
    expect(controller.getSnapshot().undoRevision).toBeNull();
  });

  it("적용이 끝나면 store 구독을 놓는다(대조: 정상 성공은 반영된다)", async () => {
    const reply = deferred<unknown>();
    const { store, controller } = setup(reply.promise);
    const unlisten = controller.listen(() => {});
    controller.sync([recalc(1031)]);
    await settle();
    controller.apply(1031);
    await settle();
    reply.resolve({ revision: 13 });
    await settle();
    expect(store.getState().revision).toBe(13);
    expect(controller.getSnapshot().undoRevision).toBe(13);
    unlisten();
  });
});

describe("B2 이전 revision의 닫기 요청은 새 되돌리기 토스트를 닫지 않는다", () => {
  it("dismissUndo(13)은 토스트가 14를 들고 있으면 무시하고, 14는 닫는다", async () => {
    const replies = [Promise.resolve({ revision: 13 }), Promise.resolve({ revision: 14 })];
    const store = createPlanStore({ api: { request: async () => planView(13) }, navigate: () => {} });
    store.acceptPlanView(planView(12));
    let call = 0;
    const api = {
      request: async (method: string) => {
        if (method === "GET") return comparison("get-comparison.200.json", { applicable: true });
        return replies[call++];
      },
    };
    const controller = createComparisonController({ api, planStore: store } as any);
    controller.listen(() => {});
    controller.sync([recalc(1031)]);
    await settle();
    controller.apply(1031);
    await settle();
    expect(controller.getSnapshot().undoRevision).toBe(13);
    store.acceptRevision(14);
    controller.dismissUndo(13);
    expect(controller.getSnapshot().undoRevision).toBeNull(); // 13 토스트는 revision 변경으로 이미 닫혔다
    // 14를 들고 있는 새 토스트가 있는 상태를 만든다.
    store.acceptPlanView(planView(14));
    controller.sync([recalc(1031), recalc(1033)]);
    await settle();
    expect(controller.getSnapshot().entries.has(1033)).toBe(true);
    controller.apply(1033);
    await settle();
    expect(controller.getSnapshot().undoRevision).toBe(14);
    controller.dismissUndo(13);
    expect(controller.getSnapshot().undoRevision).toBe(14);
    controller.dismissUndo(14);
    expect(controller.getSnapshot().undoRevision).toBeNull();
  });
});
