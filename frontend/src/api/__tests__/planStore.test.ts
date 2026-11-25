// AC-12~AC-18: 공유 계획 store (C12a-6-5, C12a-6-7, D-069, D-072).
// store 단위 테스트 5종(역전 응답 무시, NO_PLAN 무시·수용, settledStatus 직전 값 유지,
// loadPlan 동시 2회 -> 요청 1회, revision이 바뀐 뒤 -> 새 요청)이 모두 아래에 있다.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { INPUT_PATH, LOGIN_PATH } from "../../app/routes";
import { resetMocks, useMock } from "../mock";
import { deferred, jsonResponse, makeHarness, planView, problemResponse, readMock } from "./helpers";

beforeEach(() => resetMocks());

// 네트워크 없이 store 규칙만 보는 하네스(요청은 쓰지 않는다).
const bare = () => makeHarness({ mode: "mock" });

describe("초기 상태", () => {
  it("두 칸과 파생값이 모두 null이다", () => {
    expect(bare().store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
  });
});

describe("AC-12 작은 값으로 덮어쓰지 않는다 (늦게 도착한 옛 응답 무시)", () => {
  it("revision 칸은 새 값이 지금 값보다 클 때만 바뀐다", () => {
    const { store } = bare();
    store.acceptRevision(12);
    store.acceptRevision(11);
    expect(store.getState().revision).toBe(12);
    store.acceptRevision(12);
    expect(store.getState().revision).toBe(12);
    store.acceptRevision(13);
    expect(store.getState().revision).toBe(13);
  });

  it("view 칸은 새 PlanView.revision이 지금 view.revision 이상일 때만 바뀐다(같으면 교체)", () => {
    const { store } = bare();
    store.acceptPlanView(planView(13));
    store.acceptPlanView(planView(12)); // 늦게 온 옛 응답
    expect(store.getState().view).toMatchObject({ revision: 13, calcRunId: 1013, status: "CALCULATED" });
    expect(store.getState().revision).toBe(13);

    const same = planView(13, "CALCULATED");
    same.calcRunId = 1013;
    store.acceptPlanView(same);
    expect(store.getState().view?.revision).toBe(13);

    store.acceptPlanView(planView(14, "ALREADY_EXCEEDED"));
    expect(store.getState().view).toMatchObject({ revision: 14, status: "ALREADY_EXCEEDED" });
  });

  it("view 칸은 {revision, calcRunId, status}와 PlanView 본문(body)을 함께 든다", () => {
    const { store } = bare();
    const v = planView(12);
    store.acceptPlanView(v);
    expect(store.getState().view).toEqual({ revision: 12, calcRunId: 1012, status: "CALCULATED", body: v });
  });

  it("PlanView를 받으면 revision 칸도 같은 규칙으로 함께 갱신한다", () => {
    const { store } = bare();
    store.acceptPlanView(planView(12));
    expect(store.getState().revision).toBe(12);
    store.acceptRevision(14); // F-5·P-5가 revision만 먼저 바꿈
    store.acceptPlanView(planView(13)); // 늦은 GET 응답
    expect(store.getState().revision).toBe(14);
    expect(store.getState().view?.revision).toBe(13); // view는 13 >= 12 이므로 받는다
  });
});

describe("AC-13 null로 되돌리는 경우와 구독 규칙", () => {
  it("clear()는 두 칸과 파생값을 모두 null로 만든다", () => {
    const { store } = bare();
    store.acceptPlanView(planView(12));
    store.clear();
    expect(store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
  });

  it("subscribe 리스너는 상태가 실제로 바뀔 때만 불리고, 무시된 갱신에는 불리지 않는다", () => {
    const { store } = bare();
    const listener = vi.fn();
    const off = store.subscribe(listener);
    store.acceptRevision(12);
    expect(listener).toHaveBeenCalledTimes(1);
    store.acceptRevision(11); // 무시
    store.acceptRevision(12); // 같은 값
    expect(listener).toHaveBeenCalledTimes(1);
    off();
    store.acceptRevision(13);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("onRevisionChange는 최초 로드(null -> N)를 바뀜으로 받지 않는다", () => {
    const { store } = bare();
    const onChange = vi.fn();
    store.onRevisionChange(onChange);
    store.acceptPlanView(planView(12)); // null -> 12
    store.acceptRevision(12);
    expect(onChange).not.toHaveBeenCalled();

    store.acceptRevision(13);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenLastCalledWith(13, 12);

    store.acceptRevision(12); // 무시된 옛 값
    expect(onChange).toHaveBeenCalledTimes(1);

    store.clear(); // 13 -> null 은 바뀜이다(그때 가진 적용 버튼을 모두 끈다)
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(onChange).toHaveBeenLastCalledWith(null, 13);

    store.acceptRevision(20); // clear 뒤 다시 최초 로드(null -> 20)는 바뀜이 아니다
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it("onRevisionChange 구독 해제 함수가 동작한다", () => {
    const { store } = bare();
    const onChange = vi.fn();
    const off = store.onRevisionChange(onChange);
    store.acceptRevision(1);
    off();
    store.acceptRevision(2);
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("AC-14 acceptNoPlan(sentRevision)", () => {
  it("요청 시점 revision이 null이었는데 지금 값이 있으면 무시한다(그 사이 P-3 성공, 늦은 옛 응답)", () => {
    const { store, navigate } = bare();
    store.acceptPlanView(planView(12));
    expect(store.acceptNoPlan(null)).toBe(false);
    expect(store.getState().revision).toBe(12);
    expect(store.getState().view?.revision).toBe(12);
    expect(navigate).not.toHaveBeenCalled();
  });

  it("요청 시점 revision이 null이었고 지금도 null이면 수용한다(두 칸 null, 입력 화면으로)", () => {
    const { store, navigate } = bare();
    expect(store.acceptNoPlan(null)).toBe(true);
    expect(store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(INPUT_PATH);
  });

  it("그 밖의 경우(F-5·P-5가 보낸 값 12, 데모 초기화 뒤)에는 두 칸을 비우고 입력 화면으로 간다", () => {
    const { store, navigate } = bare();
    store.acceptPlanView(planView(12));
    expect(store.acceptNoPlan(12)).toBe(true);
    expect(store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
    expect(navigate).toHaveBeenCalledWith(INPUT_PATH);
  });

  it("보낸 값이 지금 값과 달라도(11 보냄, 지금 12) null이 아니었으면 수용한다", () => {
    const { store, navigate } = bare();
    store.acceptPlanView(planView(12));
    expect(store.acceptNoPlan(11)).toBe(true);
    expect(store.getState().revision).toBeNull();
    expect(navigate).toHaveBeenCalledWith(INPUT_PATH);
  });
});

describe("AC-15 settledStatus: view.revision === revision이면 view.status, 아니면 직전 값 유지", () => {
  it("D-072 시나리오: 미달 -> 초과 -> 풀림을 revision만 먼저 바뀌는 응답(F-5·P-5)과 섞어도 옛 status로 판단하지 않는다", () => {
    const { store } = bare();
    expect(store.getState().settledStatus).toBeNull();

    store.acceptPlanView(planView(12, "CALCULATED"));
    expect(store.getState().settledStatus).toBe("CALCULATED");

    store.acceptRevision(13); // F-5 성공: revision만 먼저 바뀜
    expect(store.getState().settledStatus).toBe("CALCULATED"); // 직전 값 유지

    store.acceptPlanView(planView(13, "ALREADY_EXCEEDED")); // P-4 재조회
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED");

    store.acceptRevision(14); // P-5 되돌리기 성공
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED"); // CALCULATED로 틀리지 않는다

    store.acceptPlanView(planView(14, "CALCULATED"));
    expect(store.getState().settledStatus).toBe("CALCULATED");
  });

  it("revision보다 뒤처진 view(늦게 온 옛 GET 응답)는 settledStatus를 바꾸지 않는다", () => {
    const { store } = bare();
    store.acceptPlanView(planView(12, "CALCULATED"));
    store.acceptRevision(14); // 되돌리기 등으로 revision만 앞서감
    store.acceptPlanView(planView(13, "ALREADY_EXCEEDED")); // view.revision(13) !== revision(14)
    expect(store.getState().view?.revision).toBe(13);
    expect(store.getState().settledStatus).toBe("CALCULATED"); // 직전 값 유지
    store.acceptPlanView(planView(14, "ALREADY_EXCEEDED"));
    expect(store.getState().settledStatus).toBe("ALREADY_EXCEEDED");
  });

  it("둘 다 null이면 null, revision만 있고 view가 없으면 직전 값(null) 유지", () => {
    const { store } = bare();
    store.acceptRevision(5);
    expect(store.getState().settledStatus).toBeNull();
  });

  it("clear() 뒤에는 직전 값도 지워져 null이다", () => {
    const { store } = bare();
    store.acceptPlanView(planView(12, "ALREADY_EXCEEDED"));
    store.clear();
    expect(store.getState().settledStatus).toBeNull();
    store.acceptRevision(7);
    expect(store.getState().settledStatus).toBeNull();
  });

  it("파생값 변화도 subscribe로 받는다", () => {
    const { store } = bare();
    const seen: Array<string | null> = [];
    store.subscribe((s) => seen.push(s.settledStatus));
    store.acceptPlanView(planView(12, "ALREADY_EXCEEDED"));
    expect(seen.at(-1)).toBe("ALREADY_EXCEEDED");
  });
});

describe("AC-16 loadPlan(): GET /api/plan의 유일한 호출자, single-flight", () => {
  const live = (impl: (init?: RequestInit) => Promise<Response> | Response, pathname?: string) => {
    const fetchSpy = vi.fn(async (_url: string, init?: RequestInit) => impl(init));
    return { fetchSpy, ...makeHarness({ mode: "live", fetch: fetchSpy as unknown as typeof fetch, pathname }) };
  };

  it("200이면 PlanView를 반영하고 그 PlanView를 돌려준다", async () => {
    const view = planView(12);
    const h = live(() => jsonResponse(200, view));
    await expect(h.store.loadPlan()).resolves.toEqual(view);
    expect(h.store.getState()).toMatchObject({ revision: 12, settledStatus: "CALCULATED" });
    expect(h.store.getState().view?.body).toEqual(view);
    expect(h.fetchSpy).toHaveBeenCalledTimes(1);
    expect(h.fetchSpy.mock.calls[0][0]).toBe("/api/plan");
    expect(h.fetchSpy.mock.calls[0][1]?.method).toBe("GET");
  });

  it("진행 중 요청이 있고 revision이 같으면 같은 약속을 돌려준다(동시 2회 -> 요청 1회)", async () => {
    const d = deferred();
    const h = live(() => d.promise);
    const p1 = h.store.loadPlan();
    const p2 = h.store.loadPlan();
    expect(p2).toBe(p1);
    expect(h.fetchSpy).toHaveBeenCalledTimes(1);
    d.resolve(jsonResponse(200, planView(12)));
    const [a, b] = await Promise.all([p1, p2]);
    expect(a).toEqual(b);
    expect(h.fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("revision 값이 이미 있어도 그 값에서 시작된 진행 중 요청에는 합류한다", async () => {
    const d = deferred();
    const h = live(() => d.promise);
    h.store.acceptRevision(12);
    const p1 = h.store.loadPlan();
    const p2 = h.store.loadPlan();
    expect(p2).toBe(p1);
    d.resolve(jsonResponse(200, planView(12)));
    await p1;
    expect(h.fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("끝난 뒤의 호출은 새 요청이다", async () => {
    const h = live(() => jsonResponse(200, planView(12)));
    await h.store.loadPlan();
    await h.store.loadPlan();
    expect(h.fetchSpy).toHaveBeenCalledTimes(2);
  });

  it("진행 중 요청이 시작된 뒤 revision이 바뀌었으면(F-5·P-5 성공 직후) 합류하지 않고 새로 보낸다", async () => {
    const first = deferred();
    const second = deferred();
    const queue = [first, second];
    const h = live(() => queue.shift()!.promise);
    h.store.acceptRevision(12);
    const p1 = h.store.loadPlan();
    h.store.acceptRevision(13); // F-5 성공
    const p2 = h.store.loadPlan();
    expect(p2).not.toBe(p1);
    expect(h.fetchSpy).toHaveBeenCalledTimes(2);

    // 응답 순서가 뒤바뀌어도 작은 값 무시 규칙이 정리한다
    second.resolve(jsonResponse(200, planView(13)));
    await p2;
    first.resolve(jsonResponse(200, planView(12)));
    await p1;
    expect(h.store.getState().revision).toBe(13);
    expect(h.store.getState().view?.revision).toBe(13);
  });

  it("합류하지 않은 새 요청이 진행 중이면 그 뒤의 같은 revision 호출은 그 새 요청에 합류한다", async () => {
    const first = deferred();
    const second = deferred();
    const queue = [first, second];
    const h = live(() => queue.shift()!.promise);
    h.store.acceptRevision(12);
    h.store.loadPlan();
    h.store.acceptRevision(13);
    const p2 = h.store.loadPlan();
    const p3 = h.store.loadPlan();
    expect(p3).toBe(p2);
    expect(h.fetchSpy).toHaveBeenCalledTimes(2);
  });

  it("409 NO_PLAN: 요청 시점 revision이 null이고 그대로면 수용(null 반환, 입력 화면으로)", async () => {
    const h = live(() => problemResponse(409, "NO_PLAN"), "/login");
    await expect(h.store.loadPlan()).resolves.toBeNull();
    expect(h.store.getState()).toEqual({ revision: null, view: null, settledStatus: null });
    expect(h.navigate).toHaveBeenCalledWith(INPUT_PATH);
  });

  it("409 NO_PLAN: 요청 시점 revision이 12였으면 수용해 두 칸을 비운다(데모 초기화)", async () => {
    const h = live(() => problemResponse(409, "NO_PLAN"));
    h.store.acceptPlanView(planView(12));
    await expect(h.store.loadPlan()).resolves.toBeNull();
    expect(h.store.getState().revision).toBeNull();
    expect(h.store.getState().view).toBeNull();
    expect(h.navigate).toHaveBeenCalledWith(INPUT_PATH);
  });

  it("409 NO_PLAN: 요청 시점 revision이 null이었는데 응답 전에 P-3이 성공했으면 무시한다", async () => {
    const d = deferred();
    const h = live(() => d.promise);
    const p = h.store.loadPlan(); // sentRevision = null
    h.store.acceptPlanView(planView(12)); // 그 사이 P-3 성공
    d.resolve(problemResponse(409, "NO_PLAN"));
    await expect(p).resolves.toBeNull();
    expect(h.store.getState().revision).toBe(12);
    expect(h.store.getState().view?.revision).toBe(12);
    expect(h.navigate).not.toHaveBeenCalled();
  });

  it("401 UNAUTHENTICATED: 래퍼가 상태를 비우고 로그인으로 보내며 loadPlan은 ApiError로 거절된다", async () => {
    const h = live(() => problemResponse(401, "UNAUTHENTICATED"));
    h.store.acceptRevision(12);
    await expect(h.store.loadPlan()).rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    expect(h.store.getState().revision).toBeNull();
    expect(h.navigate).toHaveBeenCalledWith(LOGIN_PATH);
  });

  it("그 밖의 오류(500·네트워크)는 상태를 바꾸지 않고 거절된다", async () => {
    const h = live(() => problemResponse(500, "INTERNAL_ERROR"));
    h.store.acceptPlanView(planView(12));
    await expect(h.store.loadPlan()).rejects.toMatchObject({ code: "INTERNAL_ERROR", status: 500 });
    expect(h.store.getState().revision).toBe(12);
    expect(h.navigate).not.toHaveBeenCalled();
  });

  it("실패한 요청은 진행 중 상태를 남기지 않는다(다음 호출은 새 요청)", async () => {
    let n = 0;
    const h = live(() => (n++ === 0 ? problemResponse(500, "INTERNAL_ERROR") : jsonResponse(200, planView(12))));
    await expect(h.store.loadPlan()).rejects.toBeDefined();
    await expect(h.store.loadPlan()).resolves.toBeDefined();
    expect(h.fetchSpy).toHaveBeenCalledTimes(2);
  });
});

describe("AC-17 목 모드: loadPlan() 동시 2회는 요청 1번, 시퀀스 응답도 한 번만 소비된다", () => {
  it("get-plan.seq-exceeded-toggle: 동시 2회 -> rev 12 하나만 소비, 다음 호출이 rev 13", async () => {
    const h = makeHarness({ mode: "mock" });
    useMock("get-plan.seq-exceeded-toggle.json");
    const [a, b] = await Promise.all([h.store.loadPlan(), h.store.loadPlan()]);
    expect(a?.revision).toBe(12);
    expect(b?.revision).toBe(12);
    const next = await h.store.loadPlan();
    expect(next?.revision).toBe(13);
    expect(next?.result.status).toBe("ALREADY_EXCEEDED");
  });

  it("개발 모드 이중 effect(같은 틱의 연속 3회)도 한 번이다", async () => {
    const h = makeHarness({ mode: "mock" });
    useMock("get-plan.seq-exceeded-toggle.json");
    await Promise.all([h.store.loadPlan(), h.store.loadPlan(), h.store.loadPlan()]);
    expect(h.store.getState().revision).toBe(12);
    expect((await h.store.loadPlan())?.revision).toBe(13);
    expect((await h.store.loadPlan())?.revision).toBe(14);
  });

  it("D-072 목 시퀀스로 본 settledStatus: 미달 -> 초과 -> (되돌리기로 revision만 14) -> 풀림", async () => {
    const h = makeHarness({ mode: "mock" });
    useMock("get-plan.seq-exceeded-toggle.json");
    await h.store.loadPlan();
    expect(h.store.getState().settledStatus).toBe("CALCULATED");
    await h.store.loadPlan(); // rev 13 초과
    expect(h.store.getState().settledStatus).toBe("ALREADY_EXCEEDED");
    const undo = await h.client.request<{ revision: number }>("POST", "/api/plan/undo", { expectedRevision: 13 });
    h.store.acceptRevision(undo.revision); // 14
    expect(h.store.getState().settledStatus).toBe("ALREADY_EXCEEDED"); // 아직 옛 status로 틀리지 않는다
    await h.store.loadPlan(); // rev 14 CALCULATED
    expect(h.store.getState().settledStatus).toBe("CALCULATED");
  });
});

describe("AC-2·AC-4·AC-12~14 기본 시퀀스로 '앱 시작 -> 로그인 -> 입력 -> 결과 -> 대화 적용 -> 되돌리기 -> 입력 수정'을 목만으로 돈다", () => {
  it("mocks/README의 9단계 표와 같이 흐른다", async () => {
    const getSeq = readMock("get-plan.seq-full-flow.json").responses;
    const putSeq = readMock("put-plan.seq-full-flow.json").responses;
    const h = makeHarness({ mode: "mock", pathname: "/" });
    const revisionChanges: Array<[number | null, number | null]> = [];
    h.store.onRevisionChange((next, prev) => revisionChanges.push([next, prev]));

    // 1 앱 시작: 401 UNAUTHENTICATED -> 로그인으로
    await expect(h.store.loadPlan()).rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    expect(h.navigate).toHaveBeenLastCalledWith(LOGIN_PATH);

    // 2 로그인
    await h.client.request("POST", "/api/auth/login", { loginId: "demo", password: "demo" });

    // 3 진입 분기: 409 NO_PLAN -> 입력 화면
    await expect(h.store.loadPlan()).resolves.toBeNull();
    expect(h.navigate).toHaveBeenLastCalledWith(INPUT_PATH);

    // 4 결과 보기: PUT -> rev 12
    const saved = await h.client.request<any>("PUT", "/api/plan", { expectedRevision: null });
    h.store.acceptPlanView(saved);
    expect(saved.revision).toBe(putSeq[0].body.revision);
    expect(h.store.getState()).toMatchObject({ revision: 12, settledStatus: "CALCULATED" });
    expect(h.store.getState().view?.calcRunId).toBe(putSeq[0].body.calcRunId);

    // 5 대화 적용: {revision: 13}
    const applied = await h.client.request<{ revision: number }>("POST", "/api/chat/messages/1031/apply", {
      expectedRevision: 12,
    });
    h.store.acceptRevision(applied.revision);
    expect(h.store.getState().revision).toBe(13);
    expect(h.store.getState().view?.revision).toBe(12); // view는 P-4가 올 때까지 12

    // 6 재조회: rev 13
    const afterApply = await h.store.loadPlan();
    expect(afterApply?.revision).toBe(getSeq[2].body.revision);
    expect(h.store.getState().view?.calcRunId).toBe(getSeq[2].body.calcRunId);

    // 7 되돌리기: {revision: 14}, 8 재조회: rev 14(마지막 응답)
    const undone = await h.client.request<{ revision: number }>("POST", "/api/plan/undo", { expectedRevision: 13 });
    h.store.acceptRevision(undone.revision);
    const afterUndo = await h.store.loadPlan();
    expect(afterUndo?.revision).toBe(14);
    expect(h.store.getState().view?.calcRunId).toBe(getSeq[3].body.calcRunId);

    // 9 입력 수정 저장: rev 15, 뒤이은 GET은 마지막 응답(rev 14)을 반복하지만 작은 값이라 무시된다
    const edited = await h.client.request<any>("PUT", "/api/plan", { expectedRevision: 14 });
    h.store.acceptPlanView(edited);
    expect(h.store.getState().revision).toBe(putSeq[1].body.revision);
    await h.store.loadPlan();
    expect(h.store.getState().revision).toBe(putSeq[1].body.revision);
    expect(h.store.getState().view?.calcRunId).toBe(putSeq[1].body.calcRunId);

    // 최초 로드(null -> 12)는 바뀜이 아니고, 이후 13·14·15만 바뀜으로 받는다
    expect(revisionChanges).toEqual([
      [13, 12],
      [14, 13],
      [15, 14],
    ]);
  });
});
