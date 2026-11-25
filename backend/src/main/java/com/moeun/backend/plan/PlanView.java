package com.moeun.backend.plan;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 현재 계획 응답. 금액은 원.
 * savingsRatioPct가 null이면 기준 비중을 쓴다. etfProductCode는 계산에 쓸 ETF 종목 코드(#154). calcStartMonth는 "yyyy-MM".
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
        String etfProductCode,
        String calcStartMonth,
        String baselineScenario,
        long revision,
        boolean canUndo,
        Instant updatedAt) {

    /**
     * 새 계획에 넣는 ETF 종목 코드(대표 상품 S&P500 추종 ETF). 입력 화면에는 종목 선택이 없다.
     * plan은 refdata에 의존하지 않아 값을 따로 두고, refdata의 대표 상품 코드와 같은지는 테스트가 확인한다.
     */
    public static final String DEFAULT_ETF_PRODUCT_CODE = "SP500";
}
