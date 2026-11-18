package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;

/**
 * 반올림한 월별 점으로 판정한다(calc-spec 8절, 계약 G-17).
 */
class ScenarioResultsTest {

    private static final YearMonth START_MONTH = YearMonth.of(2026, 11);
    private static final long GOAL = 1_000L;

    private static List<MonthlyPoint> createPoints(long... afterTaxAssets) {
        List<MonthlyPoint> points = new ArrayList<>();
        for (int k = 0; k < afterTaxAssets.length; k++) {
            points.add(new MonthlyPoint(START_MONTH.plusMonths(k), 100L * k, afterTaxAssets[k]));
        }
        return points;
    }

    @Test
    @DisplayName("목표 대비 0원이면 달성이고 도달 시점은 목표월이다")
    void achievesGoalAtZeroGap() {
        ScenarioResult result = ScenarioResults.create(Scenario.AVG, GOAL, createPoints(0, 500, 1_000));

        assertThat(result.gapToGoal()).isZero();
        assertThat(result.isAchieved()).isTrue();
        assertThat(result.goalReachMonth()).isEqualTo(START_MONTH.plusMonths(2));
    }

    @Test
    @DisplayName("중간에 목표를 넘었다가 목표월에 내려오면 미달이고 도달 시점은 처음 닿은 달이다")
    void missesGoalAfterDroppingBelowAgain() {
        ScenarioResult result = ScenarioResults.create(Scenario.BAD, GOAL, createPoints(0, 1_200, 999));

        assertThat(result.isAchieved()).isFalse();
        assertThat(result.gapToGoal()).isEqualTo(-1L);
        assertThat(result.goalReachMonth()).isEqualTo(START_MONTH.plusMonths(1));
    }

    @Test
    @DisplayName("마지막 점이 시나리오 결과이고 세후 이익은 결과 − 원금이다")
    void usesLastPointAsResult() {
        ScenarioResult result = ScenarioResults.create(Scenario.GOOD, GOAL, createPoints(0, 300, 700));

        assertThat(result.afterTaxAsset()).isEqualTo(700L);
        assertThat(result.principal()).isEqualTo(200L);
        assertThat(result.afterTaxGain()).isEqualTo(500L);
        assertThat(result.annualReturnPct()).isNull();
    }
}
