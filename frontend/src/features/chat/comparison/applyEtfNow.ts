import { applyComparison } from "../../compare/applyComparison";
import type { ComparisonDeps } from "./controller";

/**
 * ETF 버튼이 고른 종목을 AI 답을 기다리지 않고 바로 내 계획에 적용한다(#372).
 * 결과 화면 ETF 카드와 같은 P-6 COMPARE(종목만)이고, 반영은 대화 적용과 같이 공유 store에 한다(acceptRevision·loadPlan).
 * 실패(다른 곳에서 계획이 바뀜 등)는 조용히 넘기고, 계획이 바뀐 경우만 다시 불러온다.
 */
export async function applyEtfNow(deps: ComparisonDeps, productCode: string): Promise<void> {
  const store = deps.planStore;
  const sent = store.getState().revision;
  if (sent === null) return;
  const outcome = await applyComparison(
    { request: (method, path, body) => deps.api.request(method, path, body) },
    { expectedRevision: sent, changes: { etfProductCode: productCode }, path: "COMPARE" },
  );
  if (outcome.kind === "applied") {
    // 요청 중에 계획 상태가 비워졌으면(다른 계정 로그인 등) 다시 채우지 않는다
    if (store.getState().revision === null) return;
    store.acceptRevision(outcome.revision);
    store.loadPlan({ fresh: true }).catch(() => {});
  } else if (outcome.kind === "planChanged") {
    store.loadPlan({ fresh: true }).catch(() => {});
  }
}
