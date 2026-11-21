package com.moeun.backend.calc.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 비교용 방식 A(CAGR, calc-spec 7-4)를 엔진으로 구하는 방법(#162). 테스트용 가짜 종가만 쓴다.
 */
class CagrSeriesTest {

    private static final int OBSERVATION_MONTHS = 120;
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal TOLERANCE = new BigDecimal("1E-20");

    /** 첫 종가 100, 마지막 종가 200이고 그 사이는 오르내리는 121개월. */
    private static EtfSeries zigzagSeries() {
        return ReportFixtures.createSeries("TEST", "테스트 ETF", new BigDecimal("15.4"), index -> {
            if (index == 0) {
                return BigDecimal.valueOf(100);
            }
            if (index == OBSERVATION_MONTHS) {
                return BigDecimal.valueOf(200);
            }
            return BigDecimal.valueOf(index % 2 == 0 ? 130 : 90);
        });
    }

    @Test
    @DisplayName("월 수익률 g는 관측 기간의 첫 종가와 마지막 종가로 정한다: (1 + g)^120 = 마지막 ÷ 첫")
    void derivesMonthlyRateFromFirstAndLastClose() {
        BigDecimal rate = CagrSeries.calculateMonthlyRate(zigzagSeries(), OBSERVATION_MONTHS);

        BigDecimal growth = BigDecimal.ONE.add(rate).pow(OBSERVATION_MONTHS, PRECISION);
        assertThat(growth.subtract(BigDecimal.valueOf(2)).abs()).isLessThan(TOLERANCE);
    }

    @Test
    @DisplayName("관측 기간보다 앞선 종가는 쓰지 않는다(엔진의 관측 창과 같은 최근 121개)")
    void usesSameWindowAsEngine() {
        // 앞의 10달은 종가 1, 그 뒤 121개는 100 → 220. 앞선 종가를 쓰면 g가 크게 달라진다
        EtfSeries longer = ReportFixtures.createSeries("TEST", "테스트 ETF", new BigDecimal("15.4"),
                OBSERVATION_MONTHS + 10,
                index -> index < 10 ? BigDecimal.valueOf(1) : BigDecimal.valueOf(100 + index - 10));

        BigDecimal rate = CagrSeries.calculateMonthlyRate(longer, OBSERVATION_MONTHS);
        EtfSeries cagr = CagrSeries.of(longer, OBSERVATION_MONTHS);

        assertThat(BigDecimal.ONE.add(rate).pow(OBSERVATION_MONTHS, PRECISION).subtract(new BigDecimal("2.2")).abs())
                .isLessThan(TOLERANCE);
        assertThat(cagr.monthlyCloses()).hasSize(OBSERVATION_MONTHS + 1);
        assertThat(cagr.monthlyCloses().get(0).month()).isEqualTo(longer.monthlyCloses().get(10).month());
        assertThat(cagr.monthlyCloses().get(0).closeValue()).isEqualByComparingTo("100");
        assertThat(cagr.monthlyCloses().get(OBSERVATION_MONTHS).closeValue().subtract(BigDecimal.valueOf(220)).abs())
                .isLessThan(TOLERANCE);
    }

    @Test
    @DisplayName("만든 시계열은 달·첫 종가·종목이 원래와 같고, 매달 1 + g씩 올라 마지막 종가에 닿는다")
    void buildsConstantGrowthSeries() {
        EtfSeries observed = zigzagSeries();
        BigDecimal growth = BigDecimal.ONE.add(CagrSeries.calculateMonthlyRate(observed, OBSERVATION_MONTHS));

        EtfSeries cagr = CagrSeries.of(observed, OBSERVATION_MONTHS);

        List<EtfSeries.MonthlyClose> closes = cagr.monthlyCloses();
        assertThat(cagr.product()).isEqualTo(observed.product());
        assertThat(cagr.version()).isEqualTo(observed.version());
        assertThat(closes).extracting(EtfSeries.MonthlyClose::month)
                .isEqualTo(observed.monthlyCloses().stream().map(EtfSeries.MonthlyClose::month).toList());
        assertThat(closes.get(0).closeValue()).isEqualByComparingTo("100");
        assertThat(closes.get(OBSERVATION_MONTHS).closeValue().subtract(BigDecimal.valueOf(200)).abs())
                .isLessThan(TOLERANCE);
        for (int index = 1; index < closes.size(); index++) {
            BigDecimal ratio = closes.get(index).closeValue().divide(closes.get(index - 1).closeValue(), PRECISION);
            assertThat(ratio.subtract(growth).abs()).isLessThan(TOLERANCE);
        }
    }

    @Test
    @DisplayName("방식 A 시계열을 엔진에 넣으면 세 구간이 같고, 손계산(매월 초 적립, 세후)과 같다")
    void engineCollapsesToHandCalculation() {
        // 첫 종가 100, 마지막 종가 100 × 1.01^120이고 사이는 오르내린다. 방식 A의 g는 정확히 1%다
        BigDecimal last = BigDecimal.valueOf(100).multiply(new BigDecimal("1.01").pow(OBSERVATION_MONTHS));
        EtfSeries observed = ReportFixtures.createSeries("TEST", "테스트 ETF", new BigDecimal("15.4"), index -> {
            if (index == 0) {
                return BigDecimal.valueOf(100);
            }
            return index == OBSERVATION_MONTHS ? last : BigDecimal.valueOf(index % 2 == 0 ? 180 : 120);
        });
        CalcParams params = ReportFixtures.createParams(1_000);
        // ETF 100%, 보유 0원, 월 30만원, 36개월. 목표는 넉넉하게
        CalcInput input = new CalcInput(100_000_000L, 36, YearMonth.of(2026, 11), 0L, 300_000L,
                new BigDecimal("2.84"), 0, Scenario.AVG);

        CalcResult result = new CalcEngine().calculate(input, params, CagrSeries.of(observed, OBSERVATION_MONTHS));

        // 기대값은 구현이 낸 g가 아니라 글자로 적은 1%로 계산한다
        assertThat(result.scenarios()).extracting(ScenarioResult::afterTaxAsset).containsOnly(
                calculateByHand(300_000L, 36, new BigDecimal("0.01"), new BigDecimal("15.4")));
    }

    @Test
    @DisplayName("첫 종가와 마지막 종가가 같으면 g = 0이라 세후 값이 원금과 같다")
    void zeroGrowthKeepsPrincipal() {
        EtfSeries flat = ReportFixtures.createSeries("TEST", "테스트 ETF", new BigDecimal("15.4"),
                index -> BigDecimal.valueOf(index == 0 || index == OBSERVATION_MONTHS ? 100 : 100 + index));
        CalcInput input = new CalcInput(100_000_000L, 24, YearMonth.of(2026, 11), 1_000_000L, 200_000L,
                new BigDecimal("2.84"), 0, Scenario.AVG);

        CalcResult result = new CalcEngine().calculate(input, ReportFixtures.createParams(1_000),
                CagrSeries.of(flat, OBSERVATION_MONTHS));

        assertThat(CagrSeries.calculateMonthlyRate(flat, OBSERVATION_MONTHS).abs()).isLessThan(TOLERANCE);
        assertThat(result.scenarios()).extracting(ScenarioResult::afterTaxAsset)
                .containsOnly(1_000_000L + 200_000L * 24);
    }

    @Test
    @DisplayName("(예외) 종가가 관측 기간 + 1개보다 적으면 엔진과 같이 계산하지 않는다")
    void rejectsShortSeries() {
        EtfSeries shorter = ReportFixtures.createSeries("TEST", "테스트 ETF", new BigDecimal("15.4"),
                OBSERVATION_MONTHS - 1, index -> BigDecimal.valueOf(100 + index));

        assertThatThrownBy(() -> CagrSeries.of(shorter, OBSERVATION_MONTHS)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 매월 초 M씩 사고 매달 g만큼 오르면 N개월 뒤 평가액 V = M × (1 + g) × ((1 + g)^N − 1) ÷ g.
     * 차익(V − M × N)에 세율을 매긴 값을 빼고 원 단위로 반올림한다(calc-spec 6절).
     */
    private static long calculateByHand(long monthlyAmount, int months, BigDecimal rate, BigDecimal taxPct) {
        BigDecimal growth = BigDecimal.ONE.add(rate);
        BigDecimal value = BigDecimal.valueOf(monthlyAmount).multiply(growth, PRECISION)
                .multiply(growth.pow(months, PRECISION).subtract(BigDecimal.ONE), PRECISION)
                .divide(rate, PRECISION);
        BigDecimal gain = value.subtract(BigDecimal.valueOf(monthlyAmount * months));
        BigDecimal tax = gain.multiply(taxPct, PRECISION).divide(BigDecimal.valueOf(100), PRECISION);
        return value.subtract(tax).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
