import { ApiError } from "../../api/errors";
import { COPY } from "../../shared/copy/copy";
import { COMPARE_PATH, type ComparisonResult } from "./requestComparison";
import type { ComparisonView } from "./types";

/**
 * 결과 화면의 ETF 종목 비교(#156, 계약 C12a-9-3).
 *
 * 고를 수 있는 종목은 서버가 정한다(`GET /api/etf-products`, R-1). 미리보기는 조건 변경 비교
 * `POST /api/compare`에 `etfProductCode`만 실어 보내며 내 계획과 calc_run은 바뀌지 않는다.
 * 목 모드도 같은 경로를 쓴다(목 파일 `chat/post-compare.200-etf.json`).
 */
const PRODUCTS_PATH = "/api/etf-products";

/** 고를 수 있는 ETF 종목 하나(R-1). displayName은 상품명 대신 추종 지수다(CP-06). */
export interface EtfProduct {
  code: string;
  displayName: string;
  gainTaxRatePct: number;
}

export interface EtfCompareDeps {
  request(method: "GET" | "POST", path: string, body?: unknown): Promise<unknown>;
}

export type EtfProductsResult = { ok: true; products: EtfProduct[] } | { ok: false; message: string };

export async function fetchEtfProducts(deps: EtfCompareDeps): Promise<EtfProductsResult> {
  try {
    const body = (await deps.request("GET", PRODUCTS_PATH)) as { products?: unknown };
    // 계약을 어긴 200(목록 없음)도 못 가져온 것으로 다룬다. 화면이 undefined를 들고 터지지 않게 한다
    if (!Array.isArray(body?.products)) return { ok: false, message: COPY["etfCompare.listFailed"] };
    return { ok: true, products: body.products as EtfProduct[] };
  } catch {
    return { ok: false, message: COPY["etfCompare.listFailed"] };
  }
}

/** 적재하지 않은 종목은 서버가 422로 거절한다(`etfProductCode`, `NOT_AVAILABLE`). 그 밖은 다시 시도할 수 있는 실패다. */
function failureMessage(error: unknown): string {
  if (error instanceof ApiError && (error.code === "VALIDATION_FAILED" || error.code === "FIELD_NOT_CHANGEABLE")) {
    return COPY["etfCompare.notAvailable"];
  }
  return COPY["comparison.loadFailed"];
}

export async function requestEtfComparison(deps: EtfCompareDeps, productCode: string): Promise<ComparisonResult> {
  try {
    const body = await deps.request("POST", COMPARE_PATH, { conditionChanges: { etfProductCode: productCode } });
    return { ok: true, view: body as ComparisonView };
  } catch (error) {
    return { ok: false, message: failureMessage(error) };
  }
}
