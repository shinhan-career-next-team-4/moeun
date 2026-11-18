package com.moeun.backend.plan;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 현재 계획 응답. 금액은 원.
 * savingsRatioPct가 null이면 기준 비중을 쓴다. calcStartMonth는 "yyyy-MM".
 */
public record PlanView(
        long currentVersionId,
        GoalType goalType,
        String goalCustomName,
        Long goalAmount,
        Integer targetPeriodMonths,
        Long heldAssets,
        Long monthlySavingAmount,
        BigDecimal savingsRateInputPct,
        Integer savingsRatioPct,
        String calcStartMonth,
        String baselineScenario,
        long revision,
        boolean canUndo,
        Instant updatedAt) {
}
