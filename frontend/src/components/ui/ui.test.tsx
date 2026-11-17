import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";

import { Button, ChipGroup, Modal, RadioGroup, Toast, Toggle, UnitInput } from "./index";

describe("ChipGroup", () => {
  function Harness() {
    const [value, setValue] = useState<"A" | "B" | "C" | null>("A");
    return (
      <ChipGroup
        label="목표"
        options={[
          { value: "A", label: "가" },
          { value: "B", label: "나" },
          { value: "C", label: "다" },
        ]}
        value={value}
        onChange={setValue}
      />
    );
  }

  it("방향키·Home·End로 선택을 옮긴다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    expect(screen.getByRole("radiogroup", { name: "목표" })).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "가" }));
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("radio", { name: "나" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "나" })).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByRole("radio", { name: "다" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("radio", { name: "가" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{Home}");
    expect(screen.getByRole("radio", { name: "가" })).toHaveAttribute("aria-checked", "true");
  });

  it("선택이 없으면 첫 칩이 Tab으로 들어갈 수 있다", () => {
    render(<ChipGroup label="목표" options={[{ value: "A", label: "가" }, { value: "B", label: "나" }]} value={null} onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "가" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "나" })).toHaveAttribute("tabindex", "-1");
  });
});

describe("UnitInput", () => {
  it("오류가 있으면 aria-invalid와 연결된 설명을 가진다", () => {
    render(<UnitInput id="amount" label="목표 금액" unit="만원" value="" onChange={() => {}} error="값을 입력해 주세요." required />);
    const input = screen.getByRole("textbox", { name: /목표 금액/ });

    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription(/값을 입력해 주세요/);
  });

  it("도움말도 설명으로 연결한다", () => {
    render(<UnitInput id="rate" label="금리" unit="%" value="" onChange={() => {}} help="비워 두면 기본" />);
    expect(screen.getByRole("textbox", { name: "금리" })).toHaveAccessibleDescription("비워 두면 기본");
  });
});

describe("Button", () => {
  it("loading이면 disabled이고 aria-busy다", () => {
    render(<Button loading>결과 보기</Button>);
    const button = screen.getByRole("button", { name: "결과 보기" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
  });
});

describe("RadioGroup", () => {
  it("fieldset 이름과 선택 변경을 제공한다", async () => {
    const onChange = vi.fn();
    render(
      <RadioGroup
        name="period"
        legend="목표 기간이 정해져 있나요?"
        options={[{ value: "yes", label: "정해져 있어요" }, { value: "no", label: "아직 없어요" }]}
        value="yes"
        onChange={onChange}
      />,
    );
    expect(screen.getByRole("group", { name: "목표 기간이 정해져 있나요?" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "아직 없어요" }));
    expect(onChange).toHaveBeenCalledWith("no");
  });
});

describe("Toggle", () => {
  it("role switch와 aria-checked를 가진다", async () => {
    const onChange = vi.fn();
    render(<Toggle label="이미 저축 중이에요" checked={false} onChange={onChange} />);
    const toggle = screen.getByRole("switch", { name: "이미 저축 중이에요" });
    expect(toggle).toHaveAttribute("aria-checked", "false");
    await userEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith(true);
  });
});

describe("Modal", () => {
  it("열리면 첫 버튼에 포커스하고 Esc로 onClose를 부른다", async () => {
    const onClose = vi.fn();
    render(
      <Modal open title="적용할까요?" onClose={onClose} actions={<button type="button">취소</button>}>
        본문
      </Modal>,
    );
    expect(screen.getByRole("dialog", { name: "적용할까요?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "취소" })).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("닫혀 있으면 아무것도 그리지 않는다", () => {
    render(<Modal open={false} title="x" onClose={() => {}} actions={null}>본문</Modal>);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("Toast", () => {
  it("동작 버튼을 누르면 onAction을 부른다", async () => {
    const onAction = vi.fn();
    render(<Toast message="적용됐어요" actionLabel="되돌리기" onAction={onAction} onClose={() => {}} />);
    expect(screen.getByRole("status")).toHaveTextContent("적용됐어요");
    await userEvent.click(screen.getByRole("button", { name: "되돌리기" }));
    expect(onAction).toHaveBeenCalled();
  });

  it("시간이 지나면 onClose를 부른다", () => {
    vi.useFakeTimers();
    const onClose = vi.fn();
    render(<Toast message="m" onClose={onClose} durationMs={1000} />);
    vi.advanceTimersByTime(1000);
    expect(onClose).toHaveBeenCalled();
    vi.useRealTimers();
  });
});
