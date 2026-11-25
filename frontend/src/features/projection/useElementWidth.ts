import { useEffect, useRef, useState, type RefObject } from "react";

/**
 * 요소의 가로 폭(px)을 지켜보고 알려 준다. 그래프를 화면 폭에 맞춰 다시 그리려고 쓴다(#298).
 * 폭을 잴 수 없는 환경(테스트 등)에서는 fallback을 쓴다.
 */
export function useElementWidth<T extends HTMLElement>(fallback: number): [RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);

  useEffect(() => {
    const element = ref.current;
    if (element === null || typeof ResizeObserver === "undefined") return;

    // ResizeObserver는 지켜보기 시작할 때 한 번 알려 주므로 따로 먼저 재지 않는다
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width ?? 0;
      if (next > 0) setWidth(next);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return [ref, width];
}
