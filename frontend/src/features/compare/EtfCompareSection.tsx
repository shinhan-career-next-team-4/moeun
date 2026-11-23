import { useEffect, useRef, useState } from "react";
import { api } from "../../api";
import { COPY } from "../../shared/copy/copy";
import { scenarioMethodLine } from "../../shared/copy/scenarioMethod";
import { Button, ChipGroup } from "../../shared/ui";
import { CompareTable } from "./CompareTable";
import { fetchEtfProducts, requestEtfComparison, type EtfCompareDeps, type EtfProduct } from "./requestEtfComparison";
import type { ComparisonView, ConditionChanges } from "./types";

/** 적용할 거리: 미리본 비교와 그 비교가 쓴 조건·revision(C12a-9-2의 확인창이 그대로 쓴다) */
export interface EtfApplyRequest {
  comparison: ComparisonView;
  changes: ConditionChanges;
  revision: number;
}

interface EtfCompareSectionProps {
  /** 내 계획의 종목 이름(`display["assumptions.etfProductName"]`). 비면 ETF 0%라 영역을 그리지 않는다 */
  currentProductName: string;
  /** 내 계획의 종목 코드(`result.assumptions.etfProductCode`). 목록에서 지금 종목을 찾는 기준이다 */
  currentProductCode?: string;
  /** 3구간 산정 방식 코드. 고른 종목 기준의 산정 문구에 쓴다 */
  scenarioMethod: string | null;
  /** 내 계획의 revision. 바뀌면 표가 가리키는 '현재'가 더 이상 내 계획이 아니라서 결과를 지운다 */
  revision: number | null;
  /** 미리본 종목을 내 계획에 적용한다. 확인창과 P-6 호출은 결과 화면이 맡는다(C12a-9-2) */
  onApply(request: EtfApplyRequest): void;
  /** 테스트에서 바꿔 끼운다. 기본은 앱의 API 래퍼 */
  deps?: EtfCompareDeps;
}

const DEFAULT_DEPS: EtfCompareDeps = {
  request: (method, path, body) => api.request(method, path, body),
};

/**
 * 종목을 고른 뒤 비교를 보내기까지 기다리는 시간(ms).
 *
 * 칩은 radiogroup이라 방향키로 지나가는 종목마다 선택이 바뀐다(`ChipGroup`). 그대로 요청하면
 * 지나간 종목마다 서버가 3구간을 다시 계산한다. 멈춘 종목 하나만 보낸다.
 */
const COMPARE_DEBOUNCE_MS = 250;

/**
 * 결과 화면 ETF 종목 비교(#156 2단계, 계약 C12a-9-3).
 *
 * 고를 수 있는 종목은 서버가 정하고(`GET /api/etf-products`), 종목을 고르면 저장 없이 미리보기만 한다
 * (`POST /api/compare`). 적용은 사람이 '이 종목으로 바꾸기'를 누를 때 결과 화면의 확인창을 거쳐 일어난다
 * (C12a-9-2). 숫자는 모두 서버 display 문자열이고, 종목은 상품명 대신 화면 이름으로만 보여 준다(CP-06).
 */
export function EtfCompareSection({
  currentProductName,
  currentProductCode,
  scenarioMethod,
  revision,
  onApply,
  deps,
}: EtfCompareSectionProps) {
  const transport = deps ?? DEFAULT_DEPS;
  const [open, setOpen] = useState(false);
  const [products, setProducts] = useState<EtfProduct[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  const [comparing, setComparing] = useState(false);
  // 비교 결과는 그때의 revision과 함께 둔다. 내 계획이 바뀌면 표의 '현재' 열이 지금 계획과 달라 보여 주지 않는다
  const [result, setResult] = useState<{
    view: ComparisonView;
    revision: number | null;
    productCode: string;
  } | null>(null);
  const [compareError, setCompareError] = useState<string | null>(null);
  // 비교를 요청할 때마다 올린다. 먼저 보낸 요청의 늦은 응답이 나중에 고른 종목의 결과를 덮어쓰지 않게 한다
  const generation = useRef(0);
  // 목록 요청이 날아가 있는 동안 또 보내지 않는다(응답 전에 닫고 다시 열거나 다시 시도를 두 번 눌러도 한 번이다)
  const loadingProducts = useRef(false);
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => clearTimeout(debounce.current ?? undefined), []);

  // 종목 코드가 기준이다. 이름은 서버 두 곳(display와 종목 목록)이 글자까지 같아야 맞아서 코드가 없을 때만 쓴다
  const currentProduct =
    products?.find((product) => product.code === currentProductCode) ??
    products?.find((product) => product.displayName === currentProductName);

  function clearResult() {
    generation.current += 1;
    setResult(null);
    setCompareError(null);
  }

  async function loadProducts() {
    if (loadingProducts.current) return;
    loadingProducts.current = true;
    setListError(null);
    const loaded = await fetchEtfProducts(transport);
    loadingProducts.current = false;
    if (loaded.ok) {
      setProducts(loaded.products);
      return;
    }
    setListError(loaded.message);
  }

  function openSection() {
    setOpen(true);
    // 목록은 처음 열 때만 가져온다. 종목은 자주 바뀌지 않는다
    if (products === null) void loadProducts();
  }

  async function compare(productCode: string) {
    const mine = generation.current;
    const comparedAt = revision;
    const compared = await requestEtfComparison(transport, productCode);
    if (generation.current !== mine) return;
    setComparing(false);
    if (compared.ok) {
      setResult({ view: compared.view, revision: comparedAt, productCode });
      return;
    }
    setCompareError(compared.message);
  }

  function pick(productCode: string) {
    setPicked(productCode);
    clearTimeout(debounce.current ?? undefined);
    clearResult();
    if (productCode === currentProduct?.code) {
      setComparing(false);
      return;
    }
    setComparing(true);
    debounce.current = setTimeout(() => void compare(productCode), COMPARE_DEBOUNCE_MS);
  }

  if (!currentProductName) return null;

  const label = COPY["etfCompare.sectionLabel"];

  if (!open) {
    return (
      <section aria-label={label} className="flex flex-col items-start gap-3 rounded-xl border border-border bg-surface p-5">
        <p className="text-sm text-text-muted">{COPY["etfCompare.current"].replace("{product}", currentProductName)}</p>
        <Button variant="secondary" onClick={openSection}>
          {COPY["etfCompare.open"]}
        </Button>
      </section>
    );
  }

  const selected = picked ?? currentProduct?.code ?? null;
  // 안내는 사람이 직접 지금 종목을 고른 뒤에만 보여 준다(처음 열면 지금 종목이 이미 선택돼 있다)
  const isCurrentPicked = picked !== null && picked === currentProduct?.code;
  const fresh = result !== null && result.revision === revision ? result : null;
  // 결과는 남아 있는데 계획이 바뀌었다면 표의 '현재' 열이 더 이상 내 계획이 아니다
  const isStale = result !== null && result.revision !== revision;
  // 계획이 없으면(revision null) 적용할 곳이 없다
  const applyRequest: EtfApplyRequest | null =
    fresh !== null && fresh.revision !== null
      ? { comparison: fresh.view, changes: { etfProductCode: fresh.productCode }, revision: fresh.revision }
      : null;
  const comparison = fresh?.view ?? null;
  const methodText = scenarioMethodLine(scenarioMethod, comparison?.changed?.assumptions?.etfProductName ?? undefined);
  const hasChoices = products !== null && products.length > 1;

  return (
    <section aria-label={label} className="flex flex-col gap-4 rounded-xl border border-border bg-surface p-5">
      <div>
        <h2 className="text-base font-semibold text-text">{COPY["etfCompare.title"]}</h2>
        <p className="mt-1 text-xs leading-relaxed text-text-muted">{COPY["etfCompare.notice"]}</p>
      </div>

      {listError !== null && (
        <div className="flex flex-col items-start gap-2">
          <p role="status" className="text-sm text-text-muted">
            {listError}
          </p>
          <Button variant="secondary" onClick={() => void loadProducts()}>
            {COPY["etfCompare.retry"]}
          </Button>
        </div>
      )}

      {hasChoices && (
        <ChipGroup
          label={COPY["etfCompare.pickLabel"]}
          options={products.map((product) => ({ value: product.code, label: product.displayName }))}
          value={selected}
          onChange={pick}
        />
      )}

      {products !== null && !hasChoices && (
        <p role="status" className="text-sm text-text-muted">
          {COPY["etfCompare.onlyOne"]}
        </p>
      )}

      {hasChoices && isCurrentPicked && (
        <p role="status" className="text-sm text-text-muted">
          {COPY["etfCompare.sameProduct"]}
        </p>
      )}

      {isStale && (
        <p role="status" className="text-sm text-text-muted">
          {COPY["etfCompare.planChanged"]}
        </p>
      )}

      {comparing && (
        <p role="status" className="text-sm text-text-muted">
          {COPY["etfCompare.comparing"]}
        </p>
      )}

      {compareError !== null && (
        <p role="alert" className="text-sm text-danger">
          {compareError}
        </p>
      )}

      {comparison !== null && (
        <div className="flex flex-col items-start gap-3">
          {methodText && <p className="text-xs text-text-muted">{methodText}</p>}
          <CompareTable view={comparison} />
          {applyRequest !== null && (
            <Button onClick={() => onApply(applyRequest)}>{COPY["etfCompare.apply"]}</Button>
          )}
        </div>
      )}

      <Button variant="ghost" className="self-start" onClick={() => setOpen(false)}>
        {COPY["etfCompare.close"]}
      </Button>
    </section>
  );
}
