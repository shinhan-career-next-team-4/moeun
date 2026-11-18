package com.moeun.backend.calc;

import java.time.YearMonth;
import java.util.List;

/**
 * 계산 결과의 응답 모양(계약 internal-calc C2-4). 도메인 {@link CalcResult}와 같고 시나리오에 월별 점이 없다.
 * <p>calc 루트의 공개 타입이다({@link ScenarioResultView} 참고). null 값도 키를 지우지 않는다.
 *
 * @param status 이 경로의 성공 응답에서는 늘 CALCULATED
 * @param goalAmount 목표 금액
 * @param calcStartMonth 계산 시작월(현재 계획의 값)
 * @param targetMonth 목표월
 * @param baselineScenario 판정 기준 시나리오(계획의 값)
 * @param scenarios 항상 GOOD·AVG·BAD 순서. ETF 0%면 AVG 하나
 * @param requiredMonthlySaving 해커톤에서는 늘 null(D-045)
 * @param assumptions 가정 보기
 * @param dataVersions 계산에 쓴 기준 데이터·엔진 버전
 */
public record CalcResultView(CalcStatus status, long goalAmount, YearMonth calcStartMonth, YearMonth targetMonth,
        Scenario baselineScenario, List<ScenarioResultView> scenarios, Long requiredMonthlySaving,
        Assumptions assumptions, AppliedDataVersions dataVersions) {

    public static CalcResultView from(CalcResult result) {
        return new CalcResultView(result.status(), result.goalAmount(), result.calcStartMonth(), result.targetMonth(),
                result.baselineScenario(), result.scenarios().stream().map(ScenarioResultView::from).toList(),
                result.requiredMonthlySaving(), result.assumptions(), result.dataVersions());
    }
}
