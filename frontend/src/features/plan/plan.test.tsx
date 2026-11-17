import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";

import { ApiError } from "@/lib/api/client";
import * as api from "@/lib/api/endpoints";
import type { PlanView } from "@/lib/api/types";

import { ApplyConfirmModal, Disclaimer, PlanConditionBar, PlanProvider, usePlan } from "./index";
import { ResultScreen } from "./ResultScreen";

const replace = vi.fn();
let searchParams = new URLSearchParams();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  useSearchParams: () => searchParams,
}));
vi.mock("@/lib/api/endpoints", () => ({
  getPlan: vi.fn(),
  applyPlan: vi.fn(),
  undoPlan: vi.fn(),
  getInputRules: vi.fn(),
  me: vi.fn(),
}));
vi.mock("@/features/auth", () => ({ useSession: () => ({ loginId: "demo01", hasPlan: true }) }));

const plan: PlanView = {
  goalType: "JEONSE",
  goalCustomName: null,
  goalAmount: 50_000_000,
  targetPeriodMonths: 36,
  heldAssets: 6_000_000,
  monthlySaving: 1_200_000,
  savingsRatePct: 3.2,
  existingSavingsMonthly: 1_000_000,
  existingEtfMonthly: 200_000,
  ratioChoice: "KEEP_CURRENT",
  savingsRatioPct: 83,
  calcStartMonth: "2026-11",
  baselineScenario: "AVG",
  revision: 1,
  canUndo: false,
  updatedAt: "2026-11-16T00:00:00Z",
};

beforeEach(() => {
  replace.mockReset();
  searchParams = new URLSearchParams();
  vi.mocked(api.getPlan).mockResolvedValue(plan);
  vi.mocked(api.applyPlan).mockReset();
  vi.mocked(api.undoPlan).mockReset();
  vi.mocked(api.getInputRules).mockResolvedValue({
    fields: {} as never,
    goalTypes: [],
    periodPresets: [],
    baseSavingsRatioPct: 50,
  });
});

describe("PlanConditionBar", () => {
  it("목표·기간·목표 월·보유·월 저축액·금리·비중을 형식대로 보여 준다", () => {
    render(<PlanConditionBar plan={plan} baseSavingsRatioPct={50} />);
    const bar = screen.getByRole("region", { name: "내 계획" });
    expect(bar).toHaveTextContent("전세자금");
    expect(bar).toHaveTextContent("목표 5,000만원");
    expect(bar).toHaveTextContent("3년 (2029.11)");
    expect(bar).toHaveTextContent("보유 600만원");
    expect(bar).toHaveTextContent("월 120만원");
    expect(bar).toHaveTextContent("적금 금리 3.2%");
    expect(bar).toHaveTextContent("적금 83 : ETF 17");
  });

  it("기간이 없으면 '기간 미정', 금리를 비웠으면 적용 금리에 (기본) 또는 '기본금리', 비중이 없으면 기준 비중", () => {
    const open = { ...plan, targetPeriodMonths: null, savingsRatePct: null, savingsRatioPct: null, monthlySaving: null };
    const { rerender } = render(<PlanConditionBar plan={open} baseSavingsRatioPct={50} />);
    const bar = screen.getByRole("region", { name: "내 계획" });
    expect(bar).toHaveTextContent("기간 미정");
    expect(bar).toHaveTextContent("적금 금리 기본금리");
    expect(bar).toHaveTextContent("월 저축액 미정");
    expect(bar).toHaveTextContent("적금 50 : ETF 50");
    rerender(<PlanConditionBar plan={open} baseSavingsRatioPct={50} appliedRatePct={3.1} />);
    expect(bar).toHaveTextContent("적금 금리 3.1% (기본)");
  });

  it("직접 입력 목표는 목표 이름을 보여 주고, 입력 수정은 /input으로 간다", () => {
    render(<PlanConditionBar plan={{ ...plan, goalType: "CUSTOM", goalCustomName: "차 사기" }} baseSavingsRatioPct={50} />);
    expect(screen.getByRole("region", { name: "내 계획" })).toHaveTextContent("차 사기");
    expect(screen.getByRole("link", { name: "입력 수정" })).toHaveAttribute("href", "/input");
  });
});

describe("Disclaimer", () => {
  it("면책 문구 원문이 있다", () => {
    render(<Disclaimer />);
    expect(screen.getByRole("contentinfo")).toHaveTextContent(
      "본 결과는 입력하신 조건과 과거 데이터를 바탕으로 한 모의 계산이며, 실제 수령액이나 수익을 보장하지 않습니다.",
    );
  });
});

function ApplyHarness() {
  const { plan: current, apply, undo, applying } = usePlan();
  const [open, setOpen] = useState(true);
  return (
    <>
      <p>현재 {current?.monthlySaving}</p>
      <button type="button" onClick={() => void undo()}>
        직접 되돌리기
      </button>
      <ApplyConfirmModal
        open={open}
        rows={[{ label: "월 저축액", before: "120만원", after: "150만원" }]}
        confirming={applying}
        onCancel={() => setOpen(false)}
        onConfirm={async () => {
          await apply({ source: "COMPARE", changes: { monthlySaving: 1_500_000 } });
          setOpen(false);
        }}
      />
    </>
  );
}

function renderHarness() {
  render(
    <PlanProvider>
      <ApplyHarness />
    </PlanProvider>,
  );
  return userEvent.setup();
}

describe("적용·되돌리기", () => {
  it("모달 취소는 apply를 부르지 않는다", async () => {
    const user = renderHarness();
    await user.click(await screen.findByRole("button", { name: "취소" }));
    expect(api.applyPlan).not.toHaveBeenCalled();
  });

  it("적용하기를 두 번 눌러도 apply는 한 번만 호출된다", async () => {
    vi.mocked(api.applyPlan).mockReturnValue(new Promise(() => {}));
    const user = renderHarness();
    const confirm = await screen.findByRole("button", { name: "적용하기" });
    await user.click(confirm);
    await user.click(confirm);
    expect(api.applyPlan).toHaveBeenCalledTimes(1);
    expect(api.applyPlan).toHaveBeenCalledWith({ source: "COMPARE", changes: { monthlySaving: 1_500_000 }, expectedRevision: 1 });
  });

  it("적용 성공 시 토스트가 뜨고 되돌리기를 누르면 undo를 부른다", async () => {
    vi.mocked(api.applyPlan).mockResolvedValue({ ...plan, monthlySaving: 1_500_000, revision: 2, canUndo: true });
    vi.mocked(api.undoPlan).mockResolvedValue({ ...plan, revision: 3 });
    const user = renderHarness();
    await user.click(await screen.findByRole("button", { name: "적용하기" }));

    expect(await screen.findByRole("status")).toHaveTextContent("내 계획에 적용됐어요");
    expect(screen.getByText("현재 1500000")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "되돌리기" }));
    await waitFor(() => expect(api.undoPlan).toHaveBeenCalledWith(2));
    expect(await screen.findByText("현재 1200000")).toBeInTheDocument();
  });

  it("409면 안내 토스트와 함께 최신 계획으로 바뀐다", async () => {
    vi.mocked(api.applyPlan).mockRejectedValue(new ApiError(409, { plan: { ...plan, monthlySaving: 1_700_000, revision: 5 } }));
    const user = renderHarness();
    await user.click(await screen.findByRole("button", { name: "적용하기" }));

    expect(await screen.findByRole("status")).toHaveTextContent("그사이 계획이 바뀌었어요");
    expect(screen.getByText("현재 1700000")).toBeInTheDocument();
  });

  it("되돌리기 409도 최신 계획으로 바꾼다", async () => {
    vi.mocked(api.undoPlan).mockRejectedValue(new ApiError(409, { plan: { ...plan, monthlySaving: 1_800_000, revision: 4 } }));
    const user = renderHarness();
    await user.click(await screen.findByRole("button", { name: "직접 되돌리기" }));

    expect(await screen.findByText("현재 1800000")).toBeInTheDocument();
  });
});

describe("ResultScreen", () => {
  it("welcome=1이면 환영 문구와 조건 바·면책 문구를 보여 준다", async () => {
    searchParams = new URLSearchParams("welcome=1");
    render(<ResultScreen />);
    expect(await screen.findByText("demo01 님, 지난번 적용한 계획이에요.")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "내 계획" })).toBeInTheDocument();
    expect(screen.getByRole("contentinfo")).toBeInTheDocument();
  });

  it("계획이 없으면 /input으로 보낸다", async () => {
    vi.mocked(api.getPlan).mockResolvedValue(null);
    render(<ResultScreen />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/input"));
  });
});
