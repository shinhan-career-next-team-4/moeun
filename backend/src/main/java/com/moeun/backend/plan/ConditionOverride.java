package com.moeun.backend.plan;

import java.math.BigDecimal;

/** 대안·비교·대화에서 바꾼 조건. null은 바꾸지 않음. 금액은 원, 비중은 적금 %. */
public record ConditionOverride(Long monthlySavingAmount, Integer targetPeriodMonths, Integer savingsRatioPct,
        BigDecimal savingsRateInputPct, Long goalAmount, Long heldAssets) {
}
