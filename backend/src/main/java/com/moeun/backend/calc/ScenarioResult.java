package com.moeun.backend.calc;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 시나리오 하나의 결과. 금액은 원 단위로 반올림(HALF_UP)한 최종값이다.
 *
 * @param scenario 어느 구간의 결과인지
 * @param annualReturnPct 이 구간에 쓴 ETF 연 수익률(%). null = ETF 비중 0%
 * @param afterTaxAsset 목표월의 세후 예상 자산. 기한 미정이면 시계열 마지막 달의 값
 * @param principal 원금 합계(보유 자산 포함)
 * @param afterTaxGain 세후 이자·수익. afterTaxAsset - principal
 * @param gapToGoal 목표 대비. afterTaxAsset - goalAmount. 0 이상이면 달성
 * @param isAchieved 달성 여부. 목표 대비 0원은 달성이다(CP-01)
 * @param goalReachMonth 세후 예상 자산이 처음 목표 금액 이상이 되는 달. null = 계산한 기간 안에 닿지 못함
 * @param monthlyPoints 계산 시작월부터의 월별 예상 자산
 */
public record ScenarioResult(
        Scenario scenario,
        BigDecimal annualReturnPct,
        long afterTaxAsset,
        long principal,
        long afterTaxGain,
        long gapToGoal,
        boolean isAchieved,
        YearMonth goalReachMonth,
        List<MonthlyPoint> monthlyPoints) {

    public ScenarioResult {
        Objects.requireNonNull(scenario, "scenario");
        monthlyPoints = List.copyOf(monthlyPoints);
    }
}
