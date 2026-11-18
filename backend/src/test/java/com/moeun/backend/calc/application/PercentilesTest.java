package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 분위(선형 보간)와 산술평균(calc-spec 7-3, 골든 G6).
 */
class PercentilesTest {

    private static final JsonNode GOLDEN = GoldenCase.readTree("g6-percentile-and-mean.json");
    private static final BigDecimal[] VALUES = GoldenCase.readDecimals(GOLDEN.get("input").get("values")).stream()
            .sorted()
            .toArray(BigDecimal[]::new);

    private static String expected(String key) {
        return GOLDEN.get("expected").get(key).asString();
    }

    @Test
    @DisplayName("골든 G6: 하위 10%는 h = 0.9 → 100 + 0.9 × 100 = 190")
    void computesLowPercentile() {
        assertThat(Percentiles.percentile(VALUES, new BigDecimal("0.1"))).isEqualByComparingTo(expected("bad"));
    }

    @Test
    @DisplayName("골든 G6: 상위 90%는 h = 8.1 → 900 + 0.1 × 100 = 910")
    void computesHighPercentile() {
        assertThat(Percentiles.percentile(VALUES, new BigDecimal("0.9"))).isEqualByComparingTo(expected("good"));
    }

    @Test
    @DisplayName("골든 G6: 산술평균은 5,500 ÷ 10 = 550")
    void computesMean() {
        assertThat(Percentiles.mean(VALUES)).isEqualByComparingTo(expected("avg"));
    }

    @Test
    @DisplayName("0%와 100%는 가장 작은 값과 가장 큰 값이다")
    void returnsEndsAtZeroAndHundredPercent() {
        assertThat(Percentiles.percentile(VALUES, BigDecimal.ZERO)).isEqualByComparingTo("100");
        assertThat(Percentiles.percentile(VALUES, BigDecimal.ONE)).isEqualByComparingTo("1000");
    }
}
