import Link from "next/link";

import { copy } from "@/copy/ko";
import type { PlanView } from "@/lib/api/types";
import { formatManwon, formatPeriod, formatRatio, formatYearMonth } from "@/lib/format";

interface PlanConditionBarProps {
  plan: PlanView;
  baseSavingsRatioPct: number;
  /** 금리를 비웠을 때 계산 쪽이 실제로 쓴 기본금리. 아직 모르면 생략 */
  appliedRatePct?: number;
}

/** 결과 화면 상단의 내 계획 조건 한 줄(FR-RES-05) */
export function PlanConditionBar({ plan, baseSavingsRatioPct, appliedRatePct }: PlanConditionBarProps) {
  const goalName = plan.goalType === "CUSTOM" ? (plan.goalCustomName ?? "") : copy.input.goalNames[plan.goalType];
  const period =
    plan.targetPeriodMonths === null
      ? formatPeriod(null)
      : `${formatPeriod(plan.targetPeriodMonths)} (${formatYearMonth(plan.calcStartMonth, plan.targetPeriodMonths)})`;
  const items = [
    goalName,
    `목표 ${formatManwon(plan.goalAmount)}`,
    period,
    `보유 ${formatManwon(plan.heldAssets)}`,
    plan.monthlySaving === null ? copy.plan.savingUnknown : `월 ${formatManwon(plan.monthlySaving)}`,
    `적금 금리 ${rateText(plan.savingsRatePct, appliedRatePct)}`,
    formatRatio(plan.savingsRatioPct ?? baseSavingsRatioPct),
  ];

  return (
    <section aria-label={copy.plan.barLabel} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-border bg-surface px-5 py-3 text-sm text-text">
      <span className="font-bold text-primary-strong">{copy.plan.barLabel}</span>
      {items.map((item, index) => (
        <span key={index} className="after:ml-3 after:text-border after:content-['·'] last:after:content-none">
          {item}
        </span>
      ))}
      <Link href="/input" className="ml-auto rounded-lg px-3 py-1.5 font-semibold text-primary hover:bg-surface-muted">
        {copy.plan.editInput}
      </Link>
    </section>
  );
}

function rateText(inputPct: number | null, appliedPct: number | undefined): string {
  if (inputPct !== null) return `${inputPct}%`;
  if (appliedPct !== undefined) return `${appliedPct}%${copy.plan.defaultSuffix}`;
  return copy.plan.defaultRate;
}
