package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.refdata.EtfSeries.MonthlyClose;

import tools.jackson.databind.JsonNode;

/**
 * 월말 종가 → 한 달 수익률(calc-spec 7-1, 골든 G7).
 */
class MonthlyReturnsTest {

    private static List<MonthlyClose> createCloses(String... closes) {
        List<MonthlyClose> result = new ArrayList<>();
        for (int index = 0; index < closes.length; index++) {
            result.add(new MonthlyClose(YearMonth.of(2026, 7).plusMonths(index), new BigDecimal(closes[index])));
        }
        return result;
    }

    @Test
    @DisplayName("골든 G7: 종가 100, 110, 99, 103.95 → 수익률 0.10, -0.10, 0.05")
    void convertsClosesToMonthlyReturns() {
        JsonNode golden = GoldenCase.readTree("g7-monthly-returns.json");
        List<MonthlyClose> closes = new ArrayList<>();
        for (JsonNode close : golden.get("input").get("monthlyCloses")) {
            closes.add(new MonthlyClose(YearMonth.parse(close.get("month").asString()),
                    new BigDecimal(close.get("close").asString())));
        }
        List<BigDecimal> expected = GoldenCase.readDecimals(golden.get("expected").get("monthlyReturns"));

        List<BigDecimal> returns = MonthlyReturns.fromCloses(closes, closes.size() - 1);

        assertThat(returns).usingElementComparator(BigDecimal::compareTo).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("종가가 더 많으면 최근 관측 기간 + 1개 종가만 쓴다(오래된 달부터 0번)")
    void usesOnlyLatestObservationWindow() {
        List<BigDecimal> returns = MonthlyReturns.fromCloses(createCloses("50", "100", "110", "99"), 2);

        assertThat(returns).hasSize(2);
        assertThat(returns.get(0)).isEqualByComparingTo("0.10");
        assertThat(returns.get(1)).isEqualByComparingTo("-0.10");
    }

    @Test
    @DisplayName("종가가 관측 기간 + 1개보다 적으면 이유가 담긴 예외로 실패한다")
    void rejectsTooFewCloses() {
        assertThatIllegalStateException()
                .isThrownBy(() -> MonthlyReturns.fromCloses(createCloses("100", "110"), 120))
                .withMessageContaining("121");
    }
}
