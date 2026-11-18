package com.moeun.backend.plan.application;

import java.math.BigDecimal;

import com.moeun.backend.plan.GoalType;

/** 입력 화면 값 전체(계약 C12a-5-1의 요청 7필드). 금액은 원. */
public record PlanInput(
        GoalType goalType,
        String goalCustomName,
        Long goalAmount,
        Integer targetPeriodMonths,
        Long heldAssets,
        Long monthlySavingAmount,
        BigDecimal savingsRateInputPct) {
}
