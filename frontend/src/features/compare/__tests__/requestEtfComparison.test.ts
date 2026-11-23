import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../../api/errors";
import compareMock from "../../../mocks/chat/post-compare.200-etf.json";
import productsMock from "../../../mocks/refdata/get-etf-products.200.json";
import { fetchEtfProducts, requestEtfComparison } from "../requestEtfComparison";
import type { ComparisonView } from "../types";

const view = compareMock.responses[0].body as unknown as ComparisonView;
const products = productsMock.responses[0].body;

describe("fetchEtfProducts (#156, R-1)", () => {
  it("GET /api/etf-products에서 고를 수 있는 종목을 가져온다", async () => {
    const request = vi.fn().mockResolvedValue(products);

    const result = await fetchEtfProducts({ request });

    expect(request).toHaveBeenCalledWith("GET", "/api/etf-products");
    expect(result).toEqual({ ok: true, products: products.products });
  });

  it("목록을 불러오지 못하면 다시 시도 안내를 돌려준다", async () => {
    const request = vi.fn().mockRejectedValue(ApiError.network());

    const result = await fetchEtfProducts({ request });

    expect(result).toEqual({ ok: false, message: "종목 목록을 불러오지 못했어요. 다시 시도해 주세요" });
  });
});

describe("requestEtfComparison (#156, C12a-9-3)", () => {
  it("POST /api/compare에 바꿀 종목만 보내고 응답을 그대로 돌려준다", async () => {
    const request = vi.fn().mockResolvedValue(view);

    const result = await requestEtfComparison({ request }, "069500");

    expect(request).toHaveBeenCalledWith("POST", "/api/compare", {
      conditionChanges: { etfProductCode: "069500" },
    });
    expect(result).toEqual({ ok: true, view });
  });

  it("적재하지 않은 종목이면 계산할 수 없다고 안내한다", async () => {
    const request = vi.fn().mockRejectedValue(
      ApiError.fromProblem(422, {
        code: "VALIDATION_FAILED",
        errors: [{ field: "etfProductCode", reason: "NOT_AVAILABLE" }],
      }),
    );

    const result = await requestEtfComparison({ request }, "999999");

    expect(result).toEqual({ ok: false, message: "그 종목은 아직 계산할 수 없어요" });
  });

  it("그 밖의 실패는 비교 실패 안내를 돌려준다", async () => {
    const request = vi.fn().mockRejectedValue(ApiError.network());

    const result = await requestEtfComparison({ request }, "069500");

    expect(result).toEqual({ ok: false, message: "비교 결과를 불러오지 못했어요" });
  });
});
