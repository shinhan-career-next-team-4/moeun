package com.moeun.backend.plan.application;

import java.math.BigDecimal;

import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.RatioChoice;

/** 테스트용 PlanInput 빌더. 기본값은 통과하는 입력(전세 5,000만원, 3년, 보유 600만원, 월 120만원). */
public class PlanInputBuilder {

    GoalType goalType = GoalType.JEONSE;
    String goalCustomName;
    Long goalAmount = 50_000_000L;
    Integer targetPeriodMonths = 36;
    Long heldAssets = 6_000_000L;
    Long monthlySaving = 1_200_000L;
    BigDecimal savingsRatePct;
    Long existingSavingsMonthly;
    Long existingEtfMonthly;
    RatioChoice ratioChoice;

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

    public PlanInputBuilder monthlySaving(Long value) {
        monthlySaving = value;
        return this;
    }

    public PlanInputBuilder savingsRatePct(String value) {
        savingsRatePct = value == null ? null : new BigDecimal(value);
        return this;
    }

    public PlanInputBuilder existing(Long savings, Long etf) {
        existingSavingsMonthly = savings;
        existingEtfMonthly = etf;
        return this;
    }

    public PlanInputBuilder ratioChoice(RatioChoice value) {
        ratioChoice = value;
        return this;
    }

    public PlanInput build() {
        return new PlanInput(goalType, goalCustomName, goalAmount, targetPeriodMonths, heldAssets, monthlySaving,
                savingsRatePct, existingSavingsMonthly, existingEtfMonthly, ratioChoice);
    }
}
