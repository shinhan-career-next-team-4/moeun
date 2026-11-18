package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 적금 평가액(calc-spec 5절): Ms × k + u × k(k+1)/2 × (1 − 세율), u = Ms × 연금리 ÷ 100 ÷ 12.
 */
class SavingsCalculatorTest {

    private static final BigDecimal TAX_RATE_PCT = new BigDecimal("15.4");

    private final SavingsCalculator calculator =
            new SavingsCalculator(new BigDecimal("100000"), new BigDecimal("3"), TAX_RATE_PCT);

    @Test
    @DisplayName("한 달 이자 단가는 월 납입액 × 연금리 ÷ 12다(10만원·3% → 250원)")
    void computesMonthlyInterestUnit() {
        assertThat(calculator.monthlyInterestUnit()).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("납입 전(k=0) 평가액은 0이다")
    void startsAtZero() {
        assertThat(calculator.valueAt(0)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("1회차 뒤 평가액은 반올림 전 100,211.5원이다(이자 250 × 0.846)")
    void keepsFractionBeforeRounding() {
        assertThat(calculator.valueAt(1)).isEqualByComparingTo("100211.5");
    }

    @Test
    @DisplayName("12회차 만기 평가액은 원금 120만원 + 세후 이자 16,497원이다")
    void computesMaturityValue() {
        assertThat(calculator.valueAt(12)).isEqualByComparingTo("1216497");
    }

    @Test
    @DisplayName("금리가 0이면 평가액은 넣은 원금과 같다")
    void returnsPrincipalWhenRateIsZero() {
        SavingsCalculator zeroRate = new SavingsCalculator(new BigDecimal("100000"), BigDecimal.ZERO, TAX_RATE_PCT);

        assertThat(zeroRate.valueAt(12)).isEqualByComparingTo("1200000");
    }
}
