package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 부트스트랩 3구간(calc-spec 7-2, 7-3).
 */
class BootstrapScenariosTest {

    private static final BigDecimal TAX_RATE_PCT = new BigDecimal("15.4");
    private static final List<BigDecimal> MIXED_RETURNS = List.of(
            new BigDecimal("0.05"), new BigDecimal("-0.04"), new BigDecimal("0.01"), new BigDecimal("0.02"),
            new BigDecimal("-0.08"), new BigDecimal("0.03"));

    private static BootstrapScenarios createBootstrap(int pathCount, long seed) {
        return new BootstrapScenarios(MIXED_RETURNS, pathCount, seed, new BigDecimal("0.1"), new BigDecimal("0.9"));
    }

    private static BootstrapScenarios.Series run(BootstrapScenarios bootstrap, int months) {
        SavingsCalculator savings = new SavingsCalculator(new BigDecimal("50000"), new BigDecimal("3"), TAX_RATE_PCT);
        EtfPath etf = new EtfPath(new BigDecimal("50000"), TAX_RATE_PCT);
        return bootstrap.simulate(months, BigDecimal.valueOf(1_000_000), savings, etf);
    }

    @Test
    @DisplayName("경로 우선 순서로 뽑고, 시점 k마다 모든 경로의 총액에서 분위·평균을 고른다(구현과 따로 계산한 기대값)")
    void followsDrawOrderAndPerMonthPercentiles() {
        // 경로 2개 × 2개월, 수익률 2개, 적금 없음·보유 0·세율 0이라 총액 = ETF 평가액. 손으로 다시 계산할 수 있는 크기다
        List<BigDecimal> returns = List.of(new BigDecimal("0.10"), new BigDecimal("-0.20"));
        long seed = 7L;
        BootstrapScenarios bootstrap = new BootstrapScenarios(returns, 2, seed, new BigDecimal("0.1"),
                new BigDecimal("0.9"));
        SavingsCalculator noSavings = new SavingsCalculator(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        EtfPath etf = new EtfPath(new BigDecimal("100"), BigDecimal.ZERO);

        BootstrapScenarios.Series series = bootstrap.simulate(2, BigDecimal.ZERO, noSavings, etf);

        // 명세 7-2: Random(시드) 하나로 경로 1의 1·2개월, 경로 2의 1·2개월 순서로 nextInt(2)
        Random random = new Random(seed);
        int[][] picks = {{random.nextInt(2), random.nextInt(2)}, {random.nextInt(2), random.nextInt(2)}};
        double[][] values = new double[2][3];
        for (int path = 0; path < 2; path++) {
            for (int k = 1; k <= 2; k++) {
                values[path][k] = (values[path][k - 1] + 100) * (1 + returns.get(picks[path][k - 1]).doubleValue());
            }
        }
        for (int k = 1; k <= 2; k++) {
            double low = Math.min(values[0][k], values[1][k]);
            double high = Math.max(values[0][k], values[1][k]);
            // 명세 7-3: 정렬한 2개에서 h = (2 − 1) × p → 하위 10% = low + 0.1 × (high − low), 상위 90% = low + 0.9 × (high − low)
            assertThat(series.bad().get(k).doubleValue()).isCloseTo(low + 0.1 * (high - low), within(1e-9));
            assertThat(series.average().get(k).doubleValue()).isCloseTo((low + high) / 2, within(1e-9));
            assertThat(series.good().get(k).doubleValue()).isCloseTo(low + 0.9 * (high - low), within(1e-9));
        }
    }

    @Test
    @DisplayName("같은 시드면 같은 3구간이 나온다(재현)")
    void returnsSameSeriesForSameSeed() {
        assertThat(run(createBootstrap(500, 20_261_117L), 24)).isEqualTo(run(createBootstrap(500, 20_261_117L), 24));
    }

    @Test
    @DisplayName("시점마다 나쁨 ≤ 평균 ≤ 좋음이고 점은 기간 + 1개다")
    void ordersScenariosAtEveryMonth() {
        BootstrapScenarios.Series series = run(createBootstrap(500, 20_261_117L), 24);

        assertThat(series.good()).hasSize(25);
        for (int k = 0; k <= 24; k++) {
            assertThat(series.bad().get(k)).isLessThanOrEqualTo(series.average().get(k));
            assertThat(series.average().get(k)).isLessThanOrEqualTo(series.good().get(k));
        }
    }

    @Test
    @DisplayName("출발점(k=0)은 세 구간 모두 보유 자산이다")
    void startsAllScenariosFromHeldAssets() {
        BootstrapScenarios.Series series = run(createBootstrap(500, 20_261_117L), 12);

        assertThat(series.good().get(0)).isEqualByComparingTo("1000000");
        assertThat(series.average().get(0)).isEqualByComparingTo("1000000");
        assertThat(series.bad().get(0)).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("관측 수익률이 모두 같으면 경로가 하나로 모여 세 구간이 같다")
    void collapsesWhenAllReturnsAreEqual() {
        BootstrapScenarios constant = new BootstrapScenarios(List.of(new BigDecimal("0.01"), new BigDecimal("0.01")),
                300, 1L, new BigDecimal("0.1"), new BigDecimal("0.9"));

        BootstrapScenarios.Series series = run(constant, 12);

        assertThat(series.good()).usingElementComparator(BigDecimal::compareTo).isEqualTo(series.bad());
        assertThat(series.average()).usingElementComparator(BigDecimal::compareTo).isEqualTo(series.bad());
    }
}
