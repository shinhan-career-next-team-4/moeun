package com.moeun.backend.plan.application;

import java.math.BigDecimal;

import com.moeun.backend.plan.GoalType;

/** 테스트용 PlanInput 빌더. 기본값은 통과하는 입력(집 5,000만원, 3년, 보유 600만원, 월 120만원). */
public class PlanInputBuilder {

    GoalType goalType = GoalType.HOUSE;
    String goalCustomName;
    Long goalAmount = 50_000_000L;
    Integer targetPeriodMonths = 36;
    Long heldAssets = 6_000_000L;
    Long monthlySavingAmount = 1_200_000L;
    BigDecimal savingsRateInputPct;

    public static PlanInputBuilder valid() {
        return new PlanInputBuilder();
    }

    public PlanInputBuilder goalType(GoalType value) {
        goalType = value;
        return this;
    }

    public PlanInputBuilder goalCustomName(String value) {
        goalCustomName = value;
        return this;
    }

    public PlanInputBuilder goalAmount(Long value) {
        goalAmount = value;
        return this;
    }

    public PlanInputBuilder targetPeriodMonths(Integer value) {
        targetPeriodMonths = value;
        return this;
    }

    public PlanInputBuilder heldAssets(Long value) {
        heldAssets = value;
        return this;
    }

    public PlanInputBuilder monthlySavingAmount(Long value) {
        monthlySavingAmount = value;
        return this;
    }

    public PlanInputBuilder savingsRateInputPct(String value) {
        savingsRateInputPct = value == null ? null : new BigDecimal(value);
        return this;
    }

    public PlanInput build() {
        return new PlanInput(goalType, goalCustomName, goalAmount, targetPeriodMonths, heldAssets, monthlySavingAmount,
                savingsRateInputPct);
    }
}
