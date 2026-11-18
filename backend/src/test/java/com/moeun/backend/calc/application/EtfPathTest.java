package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * ETF 경로 하나(calc-spec 6절, 골든 G5): V(k) = (V(k−1) + Me) × (1 + r(k)), 점은 "그 달에 모두 판다면"의 세후 값.
 */
class EtfPathTest {

    private static final BigDecimal TAX_RATE_PCT = new BigDecimal("15.4");

    private final EtfPath path = new EtfPath(new BigDecimal("100000"), TAX_RATE_PCT);

    @Test
    @DisplayName("골든 G5: 월 10만원, +10% / -10% / +5% → 세후 0 / 108,460 / 189,000 / 302,919원")
    void matchesGoldenSinglePath() {
        JsonNode golden = GoldenCase.readTree("g5-etf-single-path.json");
        JsonNode input = golden.get("input");
        EtfPath goldenPath = new EtfPath(new BigDecimal(input.get("monthlyEtfAmount").asString()),
                new BigDecimal(input.get("taxRatePct").asString()));
        List<Long> expected = new ArrayList<>();
        golden.get("expected").get("afterTaxValues").forEach(value -> expected.add(value.asLong()));

        List<BigDecimal> values = goldenPath.afterTaxValues(GoldenCase.readDecimals(input.get("monthlyReturns")));

        assertThat(values).extracting(value -> value.setScale(0, RoundingMode.HALF_UP).longValueExact())
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("손실이면 세금이 없다(평가액 189,000 < 원금 200,000)")
    void chargesNoTaxOnLoss() {
        assertThat(path.afterTax(new BigDecimal("189000"), 2)).isEqualByComparingTo("189000");
    }

    @Test
    @DisplayName("다음 달 평가액에는 세금을 빼지 않은 값이 이어진다(303,450 = (189,000 + 100,000) × 1.05)")
    void growsPreTaxValue() {
        assertThat(path.grow(new BigDecimal("189000"), new BigDecimal("0.05"))).isEqualByComparingTo("303450");
    }
}
