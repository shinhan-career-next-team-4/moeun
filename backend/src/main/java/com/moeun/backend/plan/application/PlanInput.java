package com.moeun.backend.plan.application;

import java.math.BigDecimal;

import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.RatioChoice;

/** 입력 화면 값 전체. 금액은 원. 기존 납입액은 둘 다 null이면 토글 OFF. */
public record PlanInput(
        GoalType goalType,
        String goalCustomName,
        Long goalAmount,
        Integer targetPeriodMonths,
        Long heldAssets,
        Long monthlySaving,
        BigDecimal savingsRatePct,
        Long existingSavingsMonthly,
        Long existingEtfMonthly,
        RatioChoice ratioChoice) {

    public boolean existingOn() {
        return existingSavingsMonthly != null || existingEtfMonthly != null;
    }

    public long existingSum() {
        return orZero(existingSavingsMonthly) + orZero(existingEtfMonthly);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
