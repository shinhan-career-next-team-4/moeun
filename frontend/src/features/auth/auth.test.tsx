import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ApiError } from "@/lib/api/client";
import * as api from "@/lib/api/endpoints";

import { AppHeader } from "./AppHeader";
import { AuthGate } from "./AuthGate";
import { LoginForm } from "./LoginForm";

const replace = vi.fn();
const push = vi.fn();
const locationReplace = vi.fn();
let searchParams = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push }),
  useSearchParams: () => searchParams,
}));

vi.mock("@/lib/api/endpoints", () => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
}));

beforeEach(() => {
  replace.mockReset();
  push.mockReset();
  locationReplace.mockReset();
  vi.stubGlobal("location", { ...window.location, replace: locationReplace });
  searchParams = new URLSearchParams();
  vi.mocked(api.login).mockReset();
  vi.mocked(api.logout).mockReset();
  vi.mocked(api.me).mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function fillAndSubmit(id: string, password: string) {
  const user = userEvent.setup();
  if (id) await user.type(screen.getByLabelText("아이디"), id);
  if (password) await user.type(screen.getByLabelText("비밀번호"), password);
  await user.click(screen.getByRole("button", { name: "로그인" }));
}

describe("LoginForm", () => {
  it("로그인 성공 후 계획이 있으면 /result?welcome=1로 간다", async () => {
    vi.mocked(api.login).mockResolvedValue({ loginId: "demo01", hasPlan: true });
    render(<LoginForm />);
    await fillAndSubmit("demo01", "pw");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/result?welcome=1"));
    expect(api.login).toHaveBeenCalledWith("demo01", "pw");
  });

  it("로그인 성공 후 계획이 없으면 /input으로 간다", async () => {
    vi.mocked(api.login).mockResolvedValue({ loginId: "demo01", hasPlan: false });
    render(<LoginForm />);
    await fillAndSubmit("demo01", "pw");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/input"));
  });

  it("로그인 성공 후 비밀번호 칸을 비운다", async () => {
    // App Router가 떠난 화면을 숨긴 채 들고 있어서, 비우지 않으면 비밀번호가 세션 내내 DOM에 남는다
    vi.mocked(api.login).mockResolvedValue({ loginId: "demo01", hasPlan: false });
    render(<LoginForm />);
    await fillAndSubmit("demo01", "pw");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/input"));
    expect(screen.getByLabelText("비밀번호")).toHaveValue("");
  });

  it("로그인 실패면 안내를 보여 주고 비밀번호 칸을 비운다", async () => {
    vi.mocked(api.login).mockRejectedValue(new ApiError(401, { detail: "아이디 또는 비밀번호를 확인해 주세요." }));
    render(<LoginForm />);
    await fillAndSubmit("demo01", "wrong");
    expect(await screen.findByRole("alert")).toHaveTextContent("아이디 또는 비밀번호를 확인해 주세요.");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("");
  });

  it("서버에 연결하지 못하면 연결 안내를 보여 준다", async () => {
    vi.mocked(api.login).mockRejectedValue(new ApiError(0, { detail: "x" }));
    render(<LoginForm />);
    await fillAndSubmit("demo01", "pw");
    expect(await screen.findByRole("alert")).toHaveTextContent("서버에 연결하지 못했어요");
  });

  it("아이디나 비밀번호가 비면 제출하지 않고 안내한다", async () => {
    render(<LoginForm />);
    await fillAndSubmit("demo01", "");
    expect(screen.getByRole("alert")).toHaveTextContent("아이디와 비밀번호를 입력해 주세요.");
    expect(api.login).not.toHaveBeenCalled();
  });

  it("expired=1이면 만료 안내를 보여 준다", () => {
    searchParams = new URLSearchParams("expired=1");
    render(<LoginForm />);
    expect(screen.getByText("로그인 시간이 지나 다시 로그인해 주세요.")).toBeInTheDocument();
  });
});

describe("AuthGate", () => {
  it("로그인 상태면 자식을 그린다", async () => {
    vi.mocked(api.me).mockResolvedValue({ loginId: "demo01", hasPlan: false });
    render(<AuthGate>보호된 화면</AuthGate>);
    expect(await screen.findByText("보호된 화면")).toBeInTheDocument();
  });

  it("401이면 문서를 새로 열어 /login으로 보낸다", async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401));
    render(<AuthGate>보호된 화면</AuthGate>);
    await waitFor(() => expect(locationReplace).toHaveBeenCalledWith("/login"));
    expect(replace).not.toHaveBeenCalled();
    expect(screen.queryByText("보호된 화면")).not.toBeInTheDocument();
  });
});

describe("AppHeader", () => {
  it("아이디를 보여 주고 로그아웃하면 문서를 새로 열어 /login으로 보낸다", async () => {
    vi.mocked(api.me).mockResolvedValue({ loginId: "demo01", hasPlan: false });
    vi.mocked(api.logout).mockResolvedValue(undefined);
    render(
      <AuthGate>
        <AppHeader />
      </AuthGate>,
    );
    expect(await screen.findByText("demo01 님")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));
    // router.replace면 숨어 있던 지난 로그인 폼이 '제출 중' 상태로 되살아나 버튼이 눌리지 않는다
    await waitFor(() => expect(locationReplace).toHaveBeenCalledWith("/login"));
    expect(replace).not.toHaveBeenCalled();
    expect(api.logout).toHaveBeenCalled();
  });
});
