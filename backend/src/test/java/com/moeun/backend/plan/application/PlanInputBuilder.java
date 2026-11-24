package com.moeun.backend.plan.application;

import java.math.BigDecimal;

import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanInput;

/** 테스트용 PlanInput 빌더. 기본값은 통과하는 입력(집 5,000만원, 3년, 보유 600만원, 월 120만원). */
public class PlanInputBuilder {

    GoalType goalType = GoalType.HOUSE;
    String goalCustomName;
    BigDecimal goalAmount = BigDecimal.valueOf(50_000_000L);
    BigDecimal targetPeriodMonths = BigDecimal.valueOf(36);
    BigDecimal heldAssets = BigDecimal.valueOf(6_000_000L);
    BigDecimal monthlySavingAmount = BigDecimal.valueOf(1_200_000L);
    BigDecimal savingsRateInputPct;
    BigDecimal savingsRatioPct;

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
        goalAmount = value == null ? null : BigDecimal.valueOf(value);
        return this;
    }

    public PlanInputBuilder goalAmountRaw(BigDecimal value) {
        goalAmount = value;
        return this;
    }

    public PlanInputBuilder targetPeriodMonths(Integer value) {
        targetPeriodMonths = value == null ? null : BigDecimal.valueOf(value);
        return this;
    }

    public PlanInputBuilder heldAssets(Long value) {
        heldAssets = value == null ? null : BigDecimal.valueOf(value);
        return this;
    }

    public PlanInputBuilder monthlySavingAmount(Long value) {
        monthlySavingAmount = value == null ? null : BigDecimal.valueOf(value);
        return this;
    }

    public PlanInputBuilder savingsRateInputPct(String value) {
        savingsRateInputPct = value == null ? null : new BigDecimal(value);
        return this;
    }

    /** 비중(C12a-5-1). null이면 요청에 키가 없는 것과 같다. */
    public PlanInputBuilder savingsRatioPct(Integer value) {
        savingsRatioPct = value == null ? null : BigDecimal.valueOf(value);
        return this;
    }

    public PlanInputBuilder savingsRatioPctRaw(BigDecimal value) {
        savingsRatioPct = value;
        return this;
    }

    public PlanInput build() {
        return new PlanInput(goalType, goalCustomName, goalAmount, targetPeriodMonths, heldAssets,
                monthlySavingAmount, savingsRateInputPct, savingsRatioPct);
    }
}
