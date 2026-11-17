import { render, waitFor } from "@testing-library/react";

import { ApiError } from "@/lib/api/client";
import * as api from "@/lib/api/endpoints";

import Home from "./page";

const replace = vi.fn();

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("@/lib/api/endpoints", () => ({ me: vi.fn() }));

describe("첫 진입", () => {
  beforeEach(() => replace.mockReset());

  it("계획이 있으면 결과 화면으로 간다", async () => {
    vi.mocked(api.me).mockResolvedValue({ loginId: "demo01", hasPlan: true });
    render(<Home />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/result?welcome=1"));
  });

  it("계획이 없으면 입력 화면으로 간다", async () => {
    vi.mocked(api.me).mockResolvedValue({ loginId: "demo01", hasPlan: false });
    render(<Home />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/input"));
  });

  it("로그인하지 않았으면 로그인 화면으로 간다", async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401));
    render(<Home />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  });
});
