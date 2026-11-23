import { describe, expect, it } from "vitest";
import ratio83Mock from "../../../mocks/plan/get-plan.200-ratio83.json";
import planMock from "../../../mocks/plan/get-plan.200.json";
import type { ResultPlanBody } from "../../result/types";
import { buildPutPlanBody, emptyForm, formFromPlan, requestSavingsRatioPct, validateClientForm } from "../formState";
import type { PlanForm } from "../types";

const ratio83Plan = (ratio83Mock.responses[0].body as { plan: ResultPlanBody }).plan;
const noRatioPlan = (planMock.responses[0].body as { plan: ResultPlanBody }).plan;

function filled(patch: Partial<PlanForm> = {}): PlanForm {
  return {
    ...emptyForm(),
    goalType: "HOUSE",
    goalAmount: "2000",
    periodYears: "3",
    periodMonths: "0",
    heldAssets: "500",
    monthlySavingAmount: "30",
    ...patch,
  };
}

describe("요청 savingsRatioPct (#127, C12a-5-6 (h), D-086)", () => {
  it("① 토글 OFF면 null이다", () => {
    expect(requestSavingsRatioPct(filled({ existingSavingOn: false }), 83)).toBeNull();
  });

  it("② 토글 ON이고 납입액을 채우면 산출값이다", () => {
    const form = filled({ existingSavingOn: true, existingSavingBank: "100", existingSavingEtf: "20" });
    expect(requestSavingsRatioPct(form, null)).toBe(83);
  });

  it("③ 복원(ON·칸 비움)이면 가장 최근 응답의 비중을 가공 없이 되보낸다", () => {
    const form = filled({ existingSavingOn: true });
    expect(requestSavingsRatioPct(form, 70)).toBe(70);
    expect(requestSavingsRatioPct(form, 50)).toBe(50);
  });

  it("④ 첫 입력(OFF, 되보낼 값 없음)이면 null이다", () => {
    expect(requestSavingsRatioPct(emptyForm(), null)).toBeNull();
  });

  it("⑤ OFF에서 ON으로 켜고 칸을 채우면 산출값이다", () => {
    const form = filled({ existingSavingOn: true, existingSavingBank: "5", existingSavingEtf: "5" });
    expect(requestSavingsRatioPct(form, null)).toBe(50);
  });

  it("⑥ 어떤 경우에도 본문에 savingsRatioPct 키가 있다", () => {
    const off = buildPutPlanBody(filled({ existingSavingOn: false }), 12, 83);
    const on = buildPutPlanBody(filled({ existingSavingOn: true, existingSavingBank: "1", existingSavingEtf: "1" }), 12);
    const restored = buildPutPlanBody(filled({ existingSavingOn: true }), 12, 70);

    for (const body of [off, on, restored]) {
      expect(Object.hasOwn(body, "savingsRatioPct")).toBe(true);
    }
    expect(off.savingsRatioPct).toBeNull();
    expect(on.savingsRatioPct).toBe(50);
    expect(restored.savingsRatioPct).toBe(70);
  });

  it("ON·칸 비움인데 되보낼 값이 없으면 null이다", () => {
    expect(requestSavingsRatioPct(filled({ existingSavingOn: true }), null)).toBeNull();
  });
});

describe("토글 복원 (#127, D-089)", () => {
  it("저장된 비중이 있으면 토글이 켜지고 납입액 칸은 비어 있다", () => {
    const form = formFromPlan(ratio83Plan);

    expect(ratio83Plan.savingsRatioPct).toBe(83);
    expect(form.existingSavingOn).toBe(true);
    expect(form.existingSavingBank).toBe("");
    expect(form.existingSavingEtf).toBe("");
  });

  it("저장된 비중이 null이면 토글이 꺼진 채로 복원된다", () => {
    expect(noRatioPlan.savingsRatioPct).toBeNull();
    expect(formFromPlan(noRatioPlan).existingSavingOn).toBe(false);
  });

  it("비중이 50이어도 토글은 켜진다(null만 OFF다)", () => {
    expect(formFromPlan({ ...noRatioPlan, savingsRatioPct: 50 }).existingSavingOn).toBe(true);
  });

  it("복원된 빈 칸은 오류가 아니다", () => {
    expect(validateClientForm(filled({ existingSavingOn: true })).existingSaving).toBeUndefined();
  });

  it("납입액이 숫자가 아니면 형식 오류를 보여 준다", () => {
    const errors = validateClientForm(filled({ existingSavingOn: true, existingSavingBank: "백만" }));
    expect(errors.existingSaving).toBe("금액은 숫자로 입력해 주세요");
  });
});
