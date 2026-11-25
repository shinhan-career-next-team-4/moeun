import { COPY } from "../../shared/copy/copy";

/** 모든 결과 화면 본문에 두는 면책 문구(FR-RES-07, C12a-7). footer 영역 없이 문단으로만 보인다. */
export function Disclaimer() {
  return (
    <p data-testid="result-disclaimer" className="text-xs leading-relaxed text-text-muted">
      {COPY.disclaimer}
    </p>
  );
}
