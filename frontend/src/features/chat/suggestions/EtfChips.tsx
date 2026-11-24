import type { EtfProduct } from "../../compare/requestEtfComparison";
import { COPY, etfChipQuestion } from "../copy";

/**
 * 다른 ETF 종목으로 비교해 보는 버튼(#275, D-097). 지금 계획 종목은 빼고 서버 목록 순서대로 보여 준다.
 * 누르면 보통 질문처럼 문장을 보내고, 접수되면 그 종목을 바로 내 계획에 적용한다(#372). 고를 종목이 없으면 그리지 않는다.
 */
export function EtfChips({
  products,
  currentCode,
  disabled,
  onPick,
}: {
  products: readonly EtfProduct[];
  currentCode: string;
  disabled: boolean;
  /** 질문 문장과 고른 종목 코드. 질문을 보내고 그 종목을 바로 내 계획에 적용하는 데 쓴다(#372). */
  onPick: (question: string, productCode: string) => void;
}) {
  const others = products.filter((product) => product.code !== currentCode);
  if (others.length === 0) return null;
  return (
    <div data-testid="chat-etf-chips" role="group" aria-label={COPY.etfChipsLabel} className="mb-2 flex flex-wrap items-center gap-1.5">
      <span className="text-xs text-text-muted">{COPY.etfChipsLabel}</span>
      {others.map((product) => (
        <button
          key={product.code}
          type="button"
          data-testid="chat-etf-chip"
          disabled={disabled}
          onClick={() => onPick(etfChipQuestion(product.displayName), product.code)}
          className="rounded-full border border-border bg-surface px-2.5 py-1 text-xs text-text hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted"
        >
          {product.displayName}
        </button>
      ))}
    </div>
  );
}
