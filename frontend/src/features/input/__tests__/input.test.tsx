// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
import planMock from "../../../mocks/plan/get-plan.200.json";
import ratio83Mock from "../../../mocks/plan/get-plan.200-ratio83.json";
import { PlanInputPage } from "../PlanInputPage";

function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": contentType },
  });
}

describe("PlanInputPage (#48, C12a-5)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderInput() {
    return render(
      <MemoryRouter initialEntries={[INPUT_PATH]}>
        <Routes>
          <Route path={INPUT_PATH} element={<PlanInputPage />} />
          <Route path={LOGIN_PATH} element={<div>로그인화면</div>} />
          <Route path={RESULT_PATH} element={<div>결과화면</div>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it("409 NO_PLAN이면 빈 입력 화면이 열리고 보유 자산은 0으로 시작하고 직접 입력 칸은 목표를 고르기 전에는 없다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
    );
    renderInput();

    expect(await screen.findByText("어떤 목돈을 모으고 싶으세요?")).toBeTruthy();
    expect(screen.getByRole("radio", { name: "직접 입력" })).toBeTruthy();
    expect(screen.queryByLabelText(/목표 이름/)).toBeNull();
    expect((screen.getByLabelText(/보유 금액/) as HTMLInputElement).value).toBe("0");
    expect((screen.getByLabelText(/목표 금액/) as HTMLInputElement).value).toBe("");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    expect((screen.getByLabelText(/목표 금액/) as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("");
  });

  it("와이어프레임 02의 번호 카드와 범위 도움말을 보여 준다(rules.yml 기준)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
    );
    renderInput();

    await screen.findByText("어떤 목돈을 모으고 싶으세요?");
    expect(screen.getByText("목표를 먼저 정하면, 필요한 정보만 이어서 물어볼게요.")).toBeTruthy();
    for (const heading of ["1. 목표", "2. 목표 금액과 기간", "3. 매달 저축할 수 있는 금액", "4. 적금 금리"]) {
      expect(screen.getByRole("heading", { name: heading })).toBeTruthy();
    }
    expect(screen.getByText("목표는 1개만 선택할 수 있어요")).toBeTruthy();
    expect(screen.getByText("필수 · 1만 원 ~ 100억 원")).toBeTruthy();
    expect(screen.getByText("필수 · 1개월 ~ 30년")).toBeTruthy();
    expect(screen.getByText(/필수 · 0원 ~ 100억 원/)).toBeTruthy();
    expect(screen.getByText("필수 · 1만 원 ~ 1,000만 원")).toBeTruthy();
    expect(screen.getByText(/0~20%, 소수 둘째 자리까지/)).toBeTruthy();
  });

  async function openEmptyForm() {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
    );
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");
  }

  // D-084: 모르는 값은 화면이 대략값으로 채워 준다. 기간·월 저축액은 계속 필수다.
  describe("추천 채움 (FR-INP-04·06, D-084)", () => {
    it("'잘 모르겠어요'를 고르면 월 저축액 칸이 67만원으로 채워지고 대략값임을 알린다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "3000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "600" } });
      fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
      fireEvent.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));

      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("67");
      expect(screen.getByText(/매달 대략 67만원 이상이 필요해요/)).toBeTruthy();
    });

    it("'아직 없어요'를 고르면 기간 칸이 3년 4개월로 채워진다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "3000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "600" } });
      fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "60" } });
      fireEvent.click(screen.getByRole("radio", { name: "아직 없어요" }));

      expect((screen.getByLabelText("년") as HTMLInputElement).value).toBe("3");
      expect((screen.getByLabelText("개월") as HTMLInputElement).value).toBe("4");
      expect(screen.getByText(/대략 3년 4개월 걸려요/)).toBeTruthy();
    });

    it("추천으로 채워진 칸을 사용자가 고치면 그 값이 유지된다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "3000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "600" } });
      fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
      fireEvent.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));
      fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "80" } });

      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("80");

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "4000" } });
      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("80");
    });

    it("추천 기간이 30년을 넘으면 채우지 않고 안내만 한다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "100000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "0" } });
      fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "10" } });
      fireEvent.click(screen.getByRole("radio", { name: "아직 없어요" }));

      expect((screen.getByLabelText("년") as HTMLInputElement).value).toBe("");
      expect(screen.getByText(/30년 안에 모으기 어려워요/)).toBeTruthy();
    });

    it("추천 월 저축액이 1,000만원을 넘으면 채우지 않고 안내만 한다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "100000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "0" } });
      fireEvent.change(screen.getByLabelText("개월"), { target: { value: "1" } });
      fireEvent.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));

      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("");
      expect(screen.getByText(/매달 1,000만원이 넘게 필요해요/)).toBeTruthy();
    });

    it("둘 다 모르면 아무것도 채우지 않고 더 입력하라고 알린다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "3000" } });
      fireEvent.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));
      fireEvent.click(screen.getByRole("radio", { name: "아직 없어요" }));

      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("");
      expect((screen.getByLabelText("년") as HTMLInputElement).value).toBe("");
      expect(screen.getAllByText(/대략 얼마가 필요한지 알려 드릴게요/).length).toBe(2);
    });

    it("라디오를 '알아요'로 되돌리면 추천을 멈추고 칸이 빈다", async () => {
      await openEmptyForm();

      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "3000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "600" } });
      fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
      fireEvent.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));
      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("67");

      fireEvent.click(screen.getByRole("radio", { name: "알아요" }));
      expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("");
    });

    it("추천값 그대로 저장하면 PUT 본문에 그 값이 들어간다(요청 모양은 그대로다)", async () => {
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
        .mockResolvedValueOnce(jsonResponse(200, { revision: 1, calcRunId: 1, result: { status: "CALCULATED" } }));
      vi.stubGlobal("fetch", fetchSpy);
      renderInput();
      await screen.findByText("어떤 목돈을 모으고 싶으세요?");

      fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "3000" } });
      fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "600" } });
      fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
      fireEvent.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));
      fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

      await waitFor(() => {
        expect(screen.getByText("결과화면")).toBeTruthy();
      });
      const body = JSON.parse(fetchSpy.mock.calls[1][1].body as string);
      expect(body.monthlySavingAmount).toBe(670_000);
      expect(body.targetPeriodMonths).toBe(36);
      expect(Object.keys(body).sort()).toEqual(
        [
          "expectedRevision",
          "goalType",
          "goalCustomName",
          "goalAmount",
          "targetPeriodMonths",
          "heldAssets",
          "monthlySavingAmount",
          "savingsRateInputPct",
          "savingsRatioPct",
        ].sort(),
      );
    });
  });

  it("보유 자산을 지우고 제출하면 REQUIRED 오류가 난다(D-082)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
    );
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    const alerts = await screen.findAllByText("값을 입력해 주세요");
    expect(alerts.length).toBeGreaterThanOrEqual(4);
  });

  it("소수 5자리 이상 금액은 클라이언트 형식 오류(invalidAmountFormat)로 막는다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"));
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "12.34567" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    expect(await screen.findByText("금액은 숫자로 입력해 주세요")).toBeTruthy();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("저장 성공 시 PUT /api/plan을 부르고 결과 화면으로 이동한다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(
        jsonResponse(200, { revision: 12, calcRunId: 540, result: { status: "CALCULATED" } }),
      );
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/보유 금액/), { target: { value: "500" } });
    fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "34.5" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    await waitFor(() => {
      expect(screen.getByText("결과화면")).toBeTruthy();
    });
    const putCall = fetchSpy.mock.calls[1];
    expect(putCall[0]).toBe("/api/plan");
    expect(putCall[1].method).toBe("PUT");
    expect(JSON.parse(putCall[1].body as string)).toEqual({
      expectedRevision: null,
      goalType: "HOUSE",
      goalCustomName: null,
      goalAmount: 20_000_000,
      targetPeriodMonths: 36,
      heldAssets: 5_000_000,
      monthlySavingAmount: 345_000,
      savingsRateInputPct: null,
      savingsRatioPct: null,
    });
  });

  it("직접 입력을 고르면 목표 이름 칸이 나오고, 저장 요청에 CUSTOM과 trim한 이름이 실린다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(
        jsonResponse(200, { revision: 12, calcRunId: 540, result: { status: "CALCULATED" } }),
      );
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "직접 입력" }));
    fireEvent.change(screen.getByLabelText(/목표 이름/), { target: { value: "  내 가게 보증금  " } });
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "34.5" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    await waitFor(() => expect(screen.getByText("결과화면")).toBeTruthy());
    const body = JSON.parse(fetchSpy.mock.calls[1][1].body as string);
    expect(body.goalType).toBe("CUSTOM");
    expect(body.goalCustomName).toBe("내 가게 보증금");
  });

  it("직접 입력인데 목표 이름이 비어 있으면 저장하지 않고 필수 안내를 보인다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"));
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "직접 입력" }));
    fireEvent.change(screen.getByLabelText(/목표 이름/), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    expect(screen.getByLabelText(/목표 이름/).getAttribute("aria-invalid")).toBe("true");
    expect(fetchSpy).toHaveBeenCalledTimes(1); // 계획 조회만, PUT 없음
  });

  it("다른 목표 종류로 저장하면 goalCustomName은 null이다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(
        jsonResponse(200, { revision: 12, calcRunId: 540, result: { status: "CALCULATED" } }),
      );
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "직접 입력" }));
    fireEvent.change(screen.getByLabelText(/목표 이름/), { target: { value: "임시 이름" } });
    fireEvent.click(screen.getByRole("radio", { name: "자동차" }));
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "34.5" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    await waitFor(() => expect(screen.getByText("결과화면")).toBeTruthy());
    const body = JSON.parse(fetchSpy.mock.calls[1][1].body as string);
    expect(body.goalType).toBe("CAR");
    expect(body.goalCustomName).toBeNull();
  });

  it("422 VALIDATION_FAILED 응답의 min·max·unit을 문구로 바꿔 표시한다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(
        jsonResponse(
          422,
          {
            code: "VALIDATION_FAILED",
            errors: [
              { field: "monthlySavingAmount", reason: "OUT_OF_RANGE", min: 10_000, max: 10_000_000, unit: "KRW" },
            ],
          },
          "application/problem+json",
        ),
      );
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "0.5" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    expect(await screen.findByText("1만원~1,000만원 사이로 입력해 주세요")).toBeTruthy();
  });

  it("409 PLAN_CHANGED면 입력값을 유지하고 안내 문구를 표시한다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(jsonResponse(409, { code: "PLAN_CHANGED", errors: [] }, "application/problem+json"))
      .mockResolvedValueOnce(
        jsonResponse(200, { revision: 13, calcRunId: 553, result: { status: "CALCULATED" } }),
      );
    vi.stubGlobal("fetch", fetchSpy);
    renderInput();
    await screen.findByText("어떤 목돈을 모으고 싶으세요?");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

    expect((await screen.findByRole("alert")).textContent).toContain(
      "계획이 다른 곳에서 바뀌었어요. 다시 저장하면 입력한 값으로 바뀌어요",
    );
    expect((screen.getByLabelText(/목표 금액/) as HTMLInputElement).value).toBe("2000");
  });

  it("기존 계획이 store에 있으면 입력 수정 모드로 값을 채우고 현재 비중과 기준월 안내를 표시한다(#51)", async () => {
    const mockView = {
      revision: 12,
      calcRunId: 540,
      baselineResultScenario: "AVG",
      plan: {
        goalType: "HOUSE",
        goalCustomName: null,
        goalAmount: 20_000_000,
        targetPeriodMonths: 36,
        heldAssets: 5_000_000,
        monthlySavingAmount: 300_000,
        savingsRateInputPct: null,
        savingsRatioPct: null,
      },
      result: { status: "CALCULATED" as const },
      display: {
        calcStartMonth: "2026년 11월",
        "conditions.savingsRatioPct": "적금 50 : ETF 50",
      },
    };
    planStore.acceptPlanView(mockView);
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    renderInput();

    expect(await screen.findByText("입력 수정")).toBeTruthy();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect((screen.getByLabelText(/목표 금액/) as HTMLInputElement).value).toBe("2000");
    expect((screen.getByLabelText("년") as HTMLInputElement).value).toBe("3");
    expect((screen.getByLabelText("개월") as HTMLInputElement).value).toBe("0");
    expect((screen.getByLabelText(/보유 금액/) as HTMLInputElement).value).toBe("500");
    expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("30");
    expect((screen.getByLabelText(/적금 금리/) as HTMLInputElement).value).toBe("");
    expect(screen.getByText(/보유 자산이 2026년 11월 기준이 맞는지 다시 확인해 주세요/)).toBeTruthy();
    expect(screen.getByText(/적금 50 : ETF 50/)).toBeTruthy();
  });
  // 지금 하고 있는 저축 토글(#127, FR-INP-08, D-085·D-086·D-089)
  describe("지금 하고 있는 저축 토글 (#127)", () => {
    function noPlan() {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
      );
    }

    it("기본은 꺼져 있고 납입액 두 칸이 비활성이며 안내가 상시 보인다", async () => {
      noPlan();
      renderInput();

      await screen.findByText("어떤 목돈을 모으고 싶으세요?");
      const toggle = screen.getByLabelText("지금 하고 있는 저축이 있어요") as HTMLInputElement;
      expect(toggle.checked).toBe(false);
      expect((screen.getByLabelText("적금 월 납입액") as HTMLInputElement).disabled).toBe(true);
      expect((screen.getByLabelText("ETF 월 납입액") as HTMLInputElement).disabled).toBe(true);
      expect(screen.getByText(/끄면 기본 비중으로 계산해요/)).toBeTruthy();
    });

    it("켜면 두 칸이 열리고, 칸이 비어 있는 동안 현재 비중을 쓴다는 도움말이 보인다", async () => {
      noPlan();
      renderInput();

      await screen.findByText("어떤 목돈을 모으고 싶으세요?");
      fireEvent.click(screen.getByLabelText("지금 하고 있는 저축이 있어요"));

      expect((screen.getByLabelText("적금 월 납입액") as HTMLInputElement).disabled).toBe(false);
      expect(screen.getByText("납입액을 비워 두면 지금 비중을 그대로 써요")).toBeTruthy();

      fireEvent.change(screen.getByLabelText("적금 월 납입액"), { target: { value: "100" } });
      expect(screen.queryByText("납입액을 비워 두면 지금 비중을 그대로 써요")).toBeNull();
    });

    it("납입액을 적고 저장하면 산출한 비중을 보낸다", async () => {
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json"))
        .mockResolvedValueOnce(jsonResponse(200, planMock.responses[0].body));
      vi.stubGlobal("fetch", fetchSpy);
      renderInput();

      await screen.findByText("어떤 목돈을 모으고 싶으세요?");
      fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
      fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
      fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
      fireEvent.change(screen.getByLabelText(/월 저축 가능 금액/), { target: { value: "30" } });
      fireEvent.click(screen.getByLabelText("지금 하고 있는 저축이 있어요"));
      fireEvent.change(screen.getByLabelText("적금 월 납입액"), { target: { value: "100" } });
      fireEvent.change(screen.getByLabelText("ETF 월 납입액"), { target: { value: "20" } });
      fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));

      await waitFor(() => {
        expect(fetchSpy.mock.calls.length).toBe(2);
      });
      expect(JSON.parse(fetchSpy.mock.calls[1][1].body as string).savingsRatioPct).toBe(83);
    });

    it("저장된 비중이 있으면 토글이 켜진 채 열리고, 그대로 저장하면 그 비중을 되보낸다", async () => {
      const fetchSpy = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(200, ratio83Mock.responses[0].body))
        .mockResolvedValueOnce(jsonResponse(200, ratio83Mock.responses[0].body));
      vi.stubGlobal("fetch", fetchSpy);
      await planStore.loadPlan();
      renderInput();

      await screen.findByText("입력 수정");
      expect((screen.getByLabelText("지금 하고 있는 저축이 있어요") as HTMLInputElement).checked).toBe(true);
      expect((screen.getByLabelText("적금 월 납입액") as HTMLInputElement).value).toBe("");

      fireEvent.click(screen.getByRole("button", { name: "결과 보기" }));
      await waitFor(() => {
        expect(fetchSpy.mock.calls.length).toBe(2);
      });
      expect(JSON.parse(fetchSpy.mock.calls[1][1].body as string).savingsRatioPct).toBe(83);
    });
  });
});
