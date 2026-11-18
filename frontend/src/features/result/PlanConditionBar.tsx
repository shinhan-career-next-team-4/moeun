import { Link } from "react-router-dom";
import { INPUT_PATH } from "../../app/routes";
import { COPY } from "../../shared/copy/copy";
import { goalLabel } from "../../shared/units/units";
import type { ResultPlanView } from "./types";

interface PlanConditionBarProps {
  view: ResultPlanView;
}

/** 결과 화면 상단의 내 계획 조건 바(FR-RES-05, C12a-7). */
export function PlanConditionBar({ view }: PlanConditionBarProps) {
  const { plan, result, display } = view;
  const goalName =
    plan.goalType === "CUSTOM" && plan.goalCustomName ? plan.goalCustomName : goalLabel(plan.goalType);

  const items = [
    goalName,
    `목표 ${display["goalAmount"]}`,
    display["conditions.targetPeriodMonths"],
    `보유 ${display["conditions.heldAssets"]}`,
    `월 ${display["conditions.monthlySavingAmount"]}`,
    `적금 금리 ${display["conditions.savingsRateInputPct"]}`,
    display["conditions.savingsRatioPct"],
  ];

  return (
    <section
      aria-label="내 계획 조건"
      className="flex flex-col gap-2 rounded-xl border border-border bg-surface px-5 py-4 text-sm text-text"
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-bold text-primary-strong">내 계획 조건</span>
        {items.map((item, index) => (
          <span key={index} className="after:ml-3 after:text-border after:content-['·'] last:after:content-none">
            {item}
          </span>
        ))}
        <Link to={INPUT_PATH} className="ml-auto rounded-lg px-3 py-1.5 font-semibold text-primary hover:bg-surface-muted">
          입력 수정
        </Link>
      </div>
      {result.assumptions.isDefaultSavingsRate && (
        <p className="text-xs text-text-muted">{COPY["result.defaultSavingsRate"]}</p>
      )}
    </section>
  );
}
