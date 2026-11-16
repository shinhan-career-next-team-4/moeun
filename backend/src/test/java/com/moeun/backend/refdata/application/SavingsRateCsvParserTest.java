package com.moeun.backend.refdata.application;

import static com.moeun.backend.refdata.application.CsvFixtures.toStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.refdata.application.SavingsRateCsvParser.SavingsRate;

class SavingsRateCsvParserTest {

    private static final String HEADER = "base_month,avg_rate_pct\n";

    @Test
    @DisplayName("기준월과 평균금리를 읽는다")
    void parsesRate() {
        SavingsRate rate = SavingsRateCsvParser.parse(toStream(HEADER + "2026-10,3.25\n"));

        assertThat(rate.baseMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(rate.avgRatePct()).isEqualByComparingTo("3.25");
    }

    @Test
    @DisplayName("금리 0%와 소수 셋째 자리까지 받는다")
    void acceptsZeroAndThreeDecimalPlaces() {
        assertThat(SavingsRateCsvParser.parse(toStream(HEADER + "2026-10,0\n")).avgRatePct())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(SavingsRateCsvParser.parse(toStream(HEADER + "2026-10,3.125\n")).avgRatePct())
                .isEqualByComparingTo("3.125");
    }

    @Test
    @DisplayName("소수 넷째 자리, 음수, 너무 큰 값은 거부한다")
    void rejectsOutOfRangeRate() {
        assertThatThrownBy(() -> SavingsRateCsvParser.parse(toStream(HEADER + "2026-10,3.1255\n")))
                .hasMessage("line 2: at most 3 decimal places allowed");
        assertThatThrownBy(() -> SavingsRateCsvParser.parse(toStream(HEADER + "2026-10,-0.1\n")))
                .hasMessage("line 2: avg_rate_pct must not be negative");
        assertThatThrownBy(() -> SavingsRateCsvParser.parse(toStream(HEADER + "2026-10,1000\n")))
                .hasMessage("line 2: number is too large");
    }

    @Test
    @DisplayName("금리 줄이 정확히 하나가 아니면 거부한다")
    void rejectsMultipleRows() {
        assertThatThrownBy(() -> SavingsRateCsvParser.parse(toStream(HEADER + "2026-09,3.2\n2026-10,3.25\n")))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessage("exactly one rate row required but found 2");
    }
}
