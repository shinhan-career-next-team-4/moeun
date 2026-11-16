package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalcResultTest {

    private static final YearMonth START_MONTH = YearMonth.of(2026, 11);
    private static final long GOAL_AMOUNT = 10_000_000L;

    private static ScenarioResult createScenarioResult(Scenario scenario, long afterTaxAsset) {
        long gapToGoal = afterTaxAsset - GOAL_AMOUNT;
        return new ScenarioResult(scenario, null, afterTaxAsset, GOAL_AMOUNT, 0L, gapToGoal,
                gapToGoal >= 0, null, List.of());
    }

    private static CalcResult createResult(CalcStatus status, Scenario baseline, List<ScenarioResult> scenarios) {
        Assumptions assumptions = new Assumptions(new BigDecimal("3.25"), true, YearMonth.of(2026, 10), 50,
                new BigDecimal("15.4"), ScenarioMethod.ROLLING_DISTRIBUTION, null, List.of(ExcludedItem.EXCHANGE_RATE));
        AppliedDataVersions dataVersions = new AppliedDataVersions(1L, 2L, 3L, "test");
        return new CalcResult(status, GOAL_AMOUNT, START_MONTH, START_MONTH.plusMonths(12), baseline, scenarios,
                null, assumptions, dataVersions);
    }

    @Test
    @DisplayName("기준 시나리오와 같은 구간의 결과로 판정한다")
    void findsResultOfBaselineScenario() {
        CalcResult result = createResult(CalcStatus.CALCULATED, Scenario.BAD, List.of(
                createScenarioResult(Scenario.GOOD, 12_000_000L),
                createScenarioResult(Scenario.AVG, 10_000_000L),
                createScenarioResult(Scenario.BAD, 9_000_000L)));

        assertThat(result.findBaselineResult())
                .hasValueSatisfying(baseline -> {
                    assertThat(baseline.scenario()).isEqualTo(Scenario.BAD);
                    assertThat(baseline.isAchieved()).isFalse();
                });
    }

    @Test
    @DisplayName("ETF가 없으면 기준이 무엇이든 평균 결과로 판정한다")
    void fallsBackToAverageWithoutEtf() {
        CalcResult result = createResult(CalcStatus.CALCULATED, Scenario.BAD,
                List.of(createScenarioResult(Scenario.AVG, GOAL_AMOUNT)));

        assertThat(result.findBaselineResult())
                .hasValueSatisfying(baseline -> {
                    assertThat(baseline.scenario()).isEqualTo(Scenario.AVG);
                    assertThat(baseline.isAchieved()).isTrue();
                });
    }

    @Test
    @DisplayName("이미 목표를 넘었으면 판정할 결과가 없다")
    void hasNoBaselineResultWhenAlreadyExceeded() {
        CalcResult result = createResult(CalcStatus.ALREADY_EXCEEDED, Scenario.AVG, List.of());

        assertThat(result.findBaselineResult()).isEmpty();
    }

    @Test
    @DisplayName("결과 목록은 밖에서 바꿀 수 없다")
    void keepsListsUnmodifiable() {
        CalcResult result = createResult(CalcStatus.CALCULATED, Scenario.AVG,
                List.of(createScenarioResult(Scenario.AVG, GOAL_AMOUNT)));

        assertThat(result.scenarios()).isUnmodifiable();
        assertThat(result.assumptions().excludedItems()).isUnmodifiable();
    }
}
