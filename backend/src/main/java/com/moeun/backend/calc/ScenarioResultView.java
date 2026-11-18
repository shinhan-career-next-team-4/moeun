package com.moeun.backend.calc;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 시나리오 하나의 응답 모양(계약 internal-calc C2-4). 도메인 {@link ScenarioResult}에서 월별 점만 뺀 것이다.
 * <p>calc 루트의 공개 타입이다. planContext(#78)의 result가 같은 DTO를 쓰고 chat이 이를 참조하므로, 하위 패키지에 두면
 * 모듈 경계가 깨진다(계약 G-10). 그래프 시계열이 필요한 화면 응답은 따로 둔다.
 *
 * @param scenario 구간
 * @param annualReturnPct 이 구간 ETF 연 수익률. 부트스트랩은 하나로 정해지지 않아 null
 * @param afterTaxAsset 목표월 세후 예상 자산(원)
 * @param principal 원금 합계(보유 자산 포함)
 * @param afterTaxGain 세후 이익
 * @param gapToGoal 목표 대비. 0 이상이면 달성
 * @param isAchieved 달성 여부
 * @param goalReachMonth 처음 목표에 닿는 달. 없으면 null(키는 유지)
 */
public record ScenarioResultView(Scenario scenario, BigDecimal annualReturnPct, long afterTaxAsset, long principal,
        long afterTaxGain, long gapToGoal, @JsonProperty("isAchieved") boolean isAchieved,
        YearMonth goalReachMonth) {

    public static ScenarioResultView from(ScenarioResult result) {
        return new ScenarioResultView(result.scenario(), result.annualReturnPct(), result.afterTaxAsset(),
                result.principal(), result.afterTaxGain(), result.gapToGoal(), result.isAchieved(),
                result.goalReachMonth());
    }
}
