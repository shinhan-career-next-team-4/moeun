package com.moeun.backend.calc.application;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanView;

/**
 * 대화 조건 누적(#418, D-103)의 비교 기준. 기준 계획에 앞선 대화에서 바꾼 조건을 얹은 계획이다. 저장하지 않고 계산에만 쓴다.
 * 계획 버전 id·revision은 기준 계획 그대로라 기록(calc_run)과 적용(F-5)은 기준 계획 버전을 가리킨다.
 * 이렇게 얹은 계획을 current로 계산하면 종목 비교(D-097)·초과 판정(D-092)이 기준 계획일 때와 같은 코드로 돈다.
 */
final class BaselinePlan {

    private BaselinePlan() {
    }

    /** 비교 기준 변경이 없으면 기준 계획 그대로다. */
    static PlanView of(PlanView plan, ConditionChanges.Conversion baseline) {
        if (baseline == null) {
            return plan;
        }
        ConditionOverride changes = baseline.override();
        return new PlanView(plan.currentVersionId(), plan.goalType(),
                orElse(baseline.goalCustomName(), plan.goalCustomName()),
                orElse(changes.goalAmount(), plan.goalAmount()),
                orElse(changes.targetPeriodMonths(), plan.targetPeriodMonths()),
                plan.heldAssets(),
                orElse(changes.monthlySavingAmount(), plan.monthlySavingAmount()),
                orElse(changes.savingsRateInputPct(), plan.savingsRateInputPct()),
                orElse(changes.savingsRatioPct(), plan.savingsRatioPct()),
                orElse(baseline.etfProductCode(), plan.etfProductCode()),
                plan.calcStartMonth(), plan.baselineScenario(), plan.revision(), plan.canUndo(), plan.updatedAt());
    }

    private static <T> T orElse(T changed, T planned) {
        return changed != null ? changed : planned;
    }
}
