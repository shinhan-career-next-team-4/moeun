import { useEffect, useState } from "react";
import type { PlanState } from "../../../api/planStore";
import { type EtfCompareDeps, type EtfProduct, fetchEtfProducts } from "../../compare/requestEtfComparison";

/** 계획 종목 코드(결과 assumptions.etfProductCode). 계획이 없거나 ETF 0% 계획이면 null이다. */
export function planEtfProductCode(state: PlanState): string | null {
  const assumptions = state.view?.body.result.assumptions;
  if (typeof assumptions !== "object" || assumptions === null) return null;
  const code = (assumptions as { etfProductCode?: unknown }).etfProductCode;
  return typeof code === "string" ? code : null;
}

/**
 * 고를 수 있는 ETF 종목 목록(R-1). 계획 종목을 알 때만 받는다. ETF 0% 계획은 종목 코드가 없고 종목을 바꿔도
 * 결과가 같아 칩을 보이지 않는다. 받지 못하면 빈 목록이라 칩을 그리지 않고 대화는 그대로 쓸 수 있다.
 */
export function useEtfProducts(api: EtfCompareDeps, currentCode: string | null): readonly EtfProduct[] {
  const [products, setProducts] = useState<readonly EtfProduct[]>([]);
  useEffect(() => {
    if (currentCode === null) return;
    let active = true;
    void fetchEtfProducts(api).then((result) => {
      if (active) setProducts(result.ok ? result.products : []);
    });
    return () => {
      active = false;
    };
  }, [api, currentCode]);
  return currentCode === null ? [] : products;
}
