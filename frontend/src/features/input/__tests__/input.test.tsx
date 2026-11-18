// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { planStore } from "../../../api";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "../../../app/routes";
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

  it("409 NO_PLAN이면 빈 입력 화면이 열리고 보유 자산은 0으로 시작하며 CUSTOM은 숨긴다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
    );
    renderInput();

    expect(await screen.findByText("새 목표 세우기")).toBeTruthy();
    expect(screen.queryByRole("radio", { name: "직접 입력" })).toBeNull();
    expect((screen.getByLabelText(/보유 자산/) as HTMLInputElement).value).toBe("0");
    expect((screen.getByLabelText(/목표 금액/) as HTMLInputElement).value).toBe("");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    expect((screen.getByLabelText(/목표 금액/) as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText(/월 저축 가능 금액/) as HTMLInputElement).value).toBe("");
  });

  it("보유 자산을 지우고 제출하면 REQUIRED 오류가 난다(D-082)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(409, { code: "NO_PLAN", errors: [] }, "application/problem+json")),
    );
    renderInput();
    await screen.findByText("새 목표 세우기");

    fireEvent.change(screen.getByLabelText(/보유 자산/), { target: { value: "" } });
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
    await screen.findByText("새 목표 세우기");

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
    await screen.findByText("새 목표 세우기");

    fireEvent.click(screen.getByRole("radio", { name: "내 집 마련" }));
    fireEvent.change(screen.getByLabelText(/목표 금액/), { target: { value: "2000" } });
    fireEvent.change(screen.getByLabelText("년"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText(/보유 자산/), { target: { value: "500" } });
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
    });
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
    await screen.findByText("새 목표 세우기");

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
    await screen.findByText("새 목표 세우기");

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
});
