package com.moeun.backend.calc.application;

import java.time.YearMonth;
import java.util.List;

import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;

/**
 * 반올림한 월별 점으로 시나리오 결과와 판정을 만든다(calc-spec 8절).
 * <p>판정(목표 대비 0원 이상이면 달성)과 도달 시점이 같은 반올림 값을 쓴다. 그래서 달성이면 마지막 점이 목표 이상이고,
 * 도달 시점이 반드시 있으며 목표월 이하다(계약 G-17).
 */
final class ScenarioResults {

    private ScenarioResults() {
    }

    /**
     * @param scenario 구간
     * @param goalAmount 목표 금액
     * @param points k = 0 … N의 반올림한 월별 점. 마지막 점이 목표월이다
     */
    static ScenarioResult create(Scenario scenario, long goalAmount, List<MonthlyPoint> points) {
        MonthlyPoint last = points.get(points.size() - 1);
        long gapToGoal = last.afterTaxAsset() - goalAmount;
        YearMonth goalReachMonth = points.stream()
                .filter(point -> point.afterTaxAsset() >= goalAmount)
                .map(MonthlyPoint::month)
                .findFirst()
                .orElse(null);
        // 부트스트랩은 경로마다 수익률이 달라 연 수익률 하나가 없다. 화면·설명에도 쓰지 않는 값이라 비워 둔다
        return new ScenarioResult(scenario, null, last.afterTaxAsset(), last.principal(),
                last.afterTaxAsset() - last.principal(), gapToGoal, gapToGoal >= 0, goalReachMonth, points);
    }
}
