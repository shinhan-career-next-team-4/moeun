import { copy } from "@/copy/ko";

/** 모든 결과 화면 하단에 고정하는 면책 문구(FR-RES-07, 격식체) */
export function Disclaimer() {
  return (
    <footer className="mt-auto border-t border-border pt-4 text-xs leading-relaxed text-text-muted">
      <p>{copy.plan.disclaimer}</p>
    </footer>
  );
}
