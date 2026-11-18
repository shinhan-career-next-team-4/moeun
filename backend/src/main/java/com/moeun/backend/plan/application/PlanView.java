package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.time.Instant;

import com.moeun.backend.plan.GoalType;

/**
 * 현재 계획 응답. 금액은 원.
 * savingsRatioPct가 null이면 기준 비중을 쓴다. calcStartMonth는 "yyyy-MM".
 */
public record PlanView(
        GoalType goalType,
        String goalCustomName,
        Long goalAmount,
        Integer targetPeriodMonths,
        Long heldAssets,
        Long monthlySaving,
        BigDecimal savingsRatePct,
        Integer savingsRatioPct,
        String calcStartMonth,
        String baselineScenario,
        long revision,
        boolean canUndo,
        Instant updatedAt) {
}
