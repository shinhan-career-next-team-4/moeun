import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ApiError } from "@/lib/api/client";
import * as api from "@/lib/api/endpoints";
import type { PlanView } from "@/lib/api/types";

import { PlanInputForm } from "./PlanInputForm";
import { testRules } from "./testRules";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));
vi.mock("@/lib/api/endpoints", () => ({ getInputRules: vi.fn(), getPlan: vi.fn(), savePlan: vi.fn() }));

const savedPlan: PlanView = {
  goalType: "WEDDING",
  goalCustomName: null,
  goalAmount: 30_000_000,
  targetPeriodMonths: 12,
  heldAssets: 0,
  monthlySaving: 1_000_000,
  savingsRatePct: null,
  existingSavingsMonthly: null,
  existingEtfMonthly: null,
  ratioChoice: null,
  savingsRatioPct: null,
  calcStartMonth: "2026-11",
  baselineScenario: "AVG",
  revision: 1,
  canUndo: false,
  updatedAt: "2026-11-16T00:00:00Z",
};

beforeEach(() => {
  push.mockReset();
  vi.mocked(api.getInputRules).mockResolvedValue(testRules);
  vi.mocked(api.getPlan).mockResolvedValue(null);
  vi.mocked(api.savePlan).mockReset();
});

async function renderForm() {
  render(<PlanInputForm />);
  await screen.findByRole("radiogroup", { name: "목표" });
  return userEvent.setup();
}

describe("PlanInputForm", () => {
  it("목표를 고르기 전에는 금액 섹션이 없다", async () => {
    await renderForm();
    expect(screen.queryByLabelText(/^목표 금액/)).not.toBeInTheDocument();
  });

  it("전세자금을 고르면 10000만원·2년이 채워지고 월 저축액 416만원이 제안된다", async () => {
    const user = await renderForm();
    await user.click(screen.getByRole("radio", { name: "전세자금" }));

    expect(screen.getByLabelText(/^목표 금액/)).toHaveValue("10000");
    expect(screen.getByLabelText("년")).toHaveValue("2");
    expect(screen.getByLabelText(/월 저축 가능 금액/)).toHaveValue("416");
    expect(screen.getByText("기준 비중이면 적금 208만원 · ETF 208만원")).toBeInTheDocument();
  });

  it("월 저축액을 직접 고친 뒤 보유 자산을 바꿔도 월 저축액이 유지된다", async () => {
    const user = await renderForm();
    await user.click(screen.getByRole("radio", { name: "전세자금" }));
    const saving = screen.getByLabelText(/월 저축 가능 금액/);
    await user.clear(saving);
    await user.type(saving, "300");
    await user.type(screen.getByLabelText(/지금 이 목표에 쓸 수 있는 돈/), "600");

    expect(saving).toHaveValue("300");
  });

  it("기간 '아직 없어요'와 '잘 모르겠어요'를 함께 고르면 제출이 막히고 안내가 보인다", async () => {
    const user = await renderForm();
    await user.click(screen.getByRole("radio", { name: "전세자금" }));
    await user.type(screen.getByLabelText(/지금 이 목표에 쓸 수 있는 돈/), "0");
    await user.click(screen.getByRole("radio", { name: "아직 없어요" }));
    await user.click(screen.getByRole("radio", { name: "잘 모르겠어요" }));
    await user.click(screen.getByRole("button", { name: "결과 보기" }));

    expect(screen.getByText("목표 기간 또는 월 저축액 중 하나를 알려주세요.")).toBeInTheDocument();
    expect(api.savePlan).not.toHaveBeenCalled();
  });

  it("오류가 두 개면 요약에 2개가 나오고 첫 오류 칸에 포커스가 간다", async () => {
    const user = await renderForm();
    await user.click(screen.getByRole("radio", { name: "전세자금" }));
    await user.clear(screen.getByLabelText(/^목표 금액/));
    await user.click(screen.getByRole("button", { name: "결과 보기" }));

    expect(screen.getByRole("alert")).toHaveTextContent("확인이 필요한 항목이 2개 있어요.");
    await waitFor(() => expect(screen.getByLabelText(/^목표 금액/)).toHaveFocus());
  });

  it("기존 저축 토글을 켜고 100/20을 넣으면 비중 선택지가 83:17로 표시된다", async () => {
    const user = await renderForm();
    await user.click(screen.getByRole("radio", { name: "전세자금" }));
    await user.click(screen.getByRole("switch", { name: "이미 저축 중이에요" }));
    await user.type(screen.getByLabelText("적금 월 납입액"), "100");
    await user.type(screen.getByLabelText("ETF 월 납입액"), "20");

    expect(screen.getByRole("radio", { name: "기준 비중으로 맞추기 · 적금 50 : ETF 50" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "지금 비중 유지 · 적금 83 : ETF 17" })).toBeInTheDocument();
  });

  it("저장된 계획이 있으면 저장된 월 저축액 그대로 채워진다", async () => {
    vi.mocked(api.getPlan).mockResolvedValue(savedPlan);
    render(<PlanInputForm />);

    expect(await screen.findByLabelText(/월 저축 가능 금액/)).toHaveValue("100");
    expect(screen.getByRole("radio", { name: "결혼자금" })).toHaveAttribute("aria-checked", "true");
  });

  it("결과 보기를 누르면 저장 중에는 버튼이 비활성화되고 한 번만 호출된다", async () => {
    vi.mocked(api.getPlan).mockResolvedValue(savedPlan);
    vi.mocked(api.savePlan).mockReturnValue(new Promise(() => {}));
    const user = userEvent.setup();
    render(<PlanInputForm />);
    const submit = await screen.findByRole("button", { name: "결과 보기" });

    await user.click(submit);
    await user.click(submit);

    expect(api.savePlan).toHaveBeenCalledTimes(1);
    expect(submit).toBeDisabled();
  });

  it("저장 성공 시 /result로 이동한다", async () => {
    vi.mocked(api.getPlan).mockResolvedValue(savedPlan);
    vi.mocked(api.savePlan).mockResolvedValue(savedPlan);
    const user = userEvent.setup();
    render(<PlanInputForm />);
    await user.click(await screen.findByRole("button", { name: "결과 보기" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/result"));
    expect(api.savePlan).toHaveBeenCalledWith(expect.objectContaining({ goalType: "WEDDING", monthlySaving: 1_000_000 }));
  });

  it("서버 400 오류를 해당 칸에 표시한다", async () => {
    vi.mocked(api.getPlan).mockResolvedValue(savedPlan);
    vi.mocked(api.savePlan).mockRejectedValue(new ApiError(400, { errors: [{ field: "goalAmount", code: "OUT_OF_RANGE" }] }));
    const user = userEvent.setup();
    render(<PlanInputForm />);
    await user.click(await screen.findByRole("button", { name: "결과 보기" }));

    expect(await screen.findByText("입력할 수 있는 범위를 벗어났어요.")).toBeInTheDocument();
    expect(screen.getByLabelText(/^목표 금액/)).toHaveAttribute("aria-invalid", "true");
  });
});
