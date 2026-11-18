import { COPY } from "../../shared/copy/copy";

/** 모든 결과 화면 하단에 고정하는 면책 문구(FR-RES-07, C12a-7). */
export function Disclaimer() {
  return (
    <footer className="mt-auto border-t border-border pt-4 text-xs leading-relaxed text-text-muted">
      <p>{COPY.disclaimer}</p>
    </footer>
  );
}
