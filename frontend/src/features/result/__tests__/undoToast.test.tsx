// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { planStore } from "../../../api";
import planMock from "../../../mocks/plan/get-plan.200.json";
import { UndoToast } from "../UndoToast";

function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": contentType },
  });
}

describe("UndoToast (#51, C12a-8)", () => {
  beforeEach(() => {
    planStore.clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("되돌리기 성공 시 revision을 갱신하고 토스트를 닫으며 loadPlan을 부른다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, { revision: 14 }))
      .mockResolvedValueOnce(jsonResponse(200, { ...planMock.responses[0].body, revision: 14 }));
    vi.stubGlobal("fetch", fetchSpy);
    const onDismiss = vi.fn();

    render(<UndoToast appliedRevision={13} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole("button", { name: "되돌리기" }));

    await waitFor(() => {
      expect(onDismiss).toHaveBeenCalledTimes(1);
    });
    expect(planStore.getState().revision).toBe(14);
    expect(fetchSpy).toHaveBeenNthCalledWith(
      1,
      "/api/plan/undo",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ expectedRevision: 13 }) }),
    );
  });

  it("409 NOTHING_TO_UNDO면 토스트를 닫고 안내 문구를 전달한다", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(409, { code: "NOTHING_TO_UNDO", errors: [] }, "application/problem+json"),
      )
      .mockResolvedValueOnce(jsonResponse(200, planMock.responses[0].body));
    vi.stubGlobal("fetch", fetchSpy);
    const onDismiss = vi.fn();
    const onFeedback = vi.fn();

    render(<UndoToast appliedRevision={13} onDismiss={onDismiss} onFeedback={onFeedback} />);
    fireEvent.click(screen.getByRole("button", { name: "되돌리기" }));

    await waitFor(() => {
      expect(onDismiss).toHaveBeenCalledTimes(1);
    });
    expect(onFeedback).toHaveBeenCalledWith("이미 되돌렸어요");
  });

  it("500 INTERNAL_ERROR면 토스트를 유지하고 실패 안내를 표시한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(500, { code: "INTERNAL_ERROR", errors: [] }, "application/problem+json"),
      ),
    );
    const onDismiss = vi.fn();

    render(<UndoToast appliedRevision={13} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole("button", { name: "되돌리기" }));

    expect((await screen.findByRole("alert")).textContent).toContain("되돌리지 못했어요");
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it("공유 revision이 달라지면 토스트를 닫는다", () => {
    planStore.acceptRevision(13);
    const onDismiss = vi.fn();
    render(<UndoToast appliedRevision={13} onDismiss={onDismiss} />);

    planStore.acceptRevision(14);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});
