package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanInput;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 테스트용 PlanInput 빌더. 기본값은 통과하는 입력(집 5,000만원, 3년, 보유 600만원, 월 120만원). */
public class PlanInputBuilder {

    GoalType goalType = GoalType.HOUSE;
    String goalCustomName;
    BigDecimal goalAmount = BigDecimal.valueOf(50_000_000L);
    BigDecimal targetPeriodMonths = BigDecimal.valueOf(36);
    BigDecimal heldAssets = BigDecimal.valueOf(6_000_000L);
    BigDecimal monthlySavingAmount = BigDecimal.valueOf(1_200_000L);
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

    public PlanInput build() {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        putIfNotNull(fields, "goalAmount", goalAmount);
        putIfNotNull(fields, "targetPeriodMonths", targetPeriodMonths);
        putIfNotNull(fields, "heldAssets", heldAssets);
        putIfNotNull(fields, "monthlySavingAmount", monthlySavingAmount);
        putIfNotNull(fields, "savingsRateInputPct", savingsRateInputPct);
        return new PlanInput(goalType, goalCustomName, fields);
    }

    private static void putIfNotNull(Map<String, JsonNode> fields, String key, BigDecimal value) {
        if (value != null) {
            fields.put(key, JsonNodeFactory.instance.numberNode(value));
        }
    }
}
