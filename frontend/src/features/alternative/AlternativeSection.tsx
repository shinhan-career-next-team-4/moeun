import { COPY } from "../../shared/copy/copy";
import { AlternativeCard } from "./AlternativeCard";
import type { AlternativeKey, AlternativeValues, AlternativeValuesByKey } from "./types";

/** 카드 순서는 와이어프레임과 같다. 순서는 우열이 아니다(FR-ALT-02 머리글). */
const CARD_ORDER: AlternativeKey[] = ["monthlySavingAmount", "targetPeriodMonths", "savingsRatioPct"];

interface AlternativeSectionProps {
  /** 기준 시나리오로 목표에 닿았는지(FR-ALT-01). 달성이면 대안 영역을 그리지 않는다 */
  isAchieved: boolean;
  /** 대안 산출 API(#66)가 준 display 문자열. 없으면 카드는 빈 카드다 */
  values?: AlternativeValuesByKey;
  /** '비교하기'를 누른 대안. 비교 영역에 조건을 채운다(FR-ALT-04) */
  onCompare?: (values: AlternativeValues) => void;
  /** '내 계획으로 적용'을 누른 대안(#258) */
  onApply?: (values: AlternativeValues) => void;
  applyEnabled?: boolean;
  onPeriodSubmit?: (months: number) => void;
  /** 기한 카드의 범위 안내(A-1-2) */
  periodError?: string | null;
  /** 대안 API가 실패했는지(A-1-2). 카드는 비고 안내만 보인다 */
  loadFailed?: boolean;
}

/** 대안 영역(FR-ALT-01·02, 와이어프레임 03). 미달일 때만 보인다. */
export function AlternativeSection({
  isAchieved,
  values,
  onCompare,
  onApply,
  applyEnabled,
  onPeriodSubmit,
  periodError,
  loadFailed = false,
}: AlternativeSectionProps) {
  if (isAchieved) return null;

  return (
    <section aria-label="대안" className="flex flex-col gap-3">
      <div>
        <h2 className="text-base font-semibold text-text">{COPY["alt.title"]}</h2>
        <p className="mt-1 text-xs leading-relaxed text-text-muted">{COPY["alt.header"]}</p>
      </div>
      {loadFailed && (
        <p role="status" className="text-sm text-text-muted">
          {COPY["alt.loadFailed"]}
        </p>
      )}
      <ul className="grid gap-3 sm:grid-cols-3">
        {CARD_ORDER.map((key) => (
          <AlternativeCard
            key={key}
            alternativeKey={key}
            values={values?.[key]}
            onCompare={onCompare}
            onApply={onApply}
            applyEnabled={applyEnabled}
            onPeriodSubmit={key === "targetPeriodMonths" ? onPeriodSubmit : undefined}
            periodError={key === "targetPeriodMonths" ? periodError : undefined}
          />
        ))}
      </ul>
    </section>
  );
}
