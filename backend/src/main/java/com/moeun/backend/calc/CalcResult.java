package com.moeun.backend.calc;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 계산 결과. 내 계획 결과·비교·대화 재계산이 모두 이 형태를 받는다.
 * AI는 이 숫자를 설명만 하고 바꾸지 않는다(NFR-INT-01).
 *
 * @param status 계산을 했는지, 이미 목표를 넘어 계산하지 않았는지
 * @param goalAmount 목표 금액(그래프의 목표선)
 * @param calcStartMonth 계산 시작월
 * @param targetMonth 목표월. null = 기한 미정
 * @param baselineScenario 달성/미달 판정에 쓴 기준 시나리오
 * @param scenarios 시나리오별 결과. ETF 포함이면 GOOD·AVG·BAD, ETF 0%면 AVG 하나, ALREADY_EXCEEDED면 빈 목록
 * @param requiredMonthlySaving 목표 기간 안에 목표에 닿는 데 필요한 월 저축액(FR-RES-08).
 *     null = 구하지 않음(월 저축액을 입력했거나 기한 미정) 또는 허용 범위 안에서 닿을 수 없음
 * @param assumptions 가정 보기
 * @param dataVersions 계산에 쓴 기준 데이터 버전
 */
public record CalcResult(
        CalcStatus status,
        long goalAmount,
        YearMonth calcStartMonth,
        YearMonth targetMonth,
        Scenario baselineScenario,
        List<ScenarioResult> scenarios,
        Long requiredMonthlySaving,
        Assumptions assumptions,
        AppliedDataVersions dataVersions) {

    public CalcResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(calcStartMonth, "calcStartMonth");
        Objects.requireNonNull(baselineScenario, "baselineScenario");
        Objects.requireNonNull(assumptions, "assumptions");
        Objects.requireNonNull(dataVersions, "dataVersions");
        scenarios = List.copyOf(scenarios);
    }

    /**
     * 판정에 쓸 시나리오 결과를 찾는다.
     * ETF 0%면 결과가 AVG 하나뿐이라, 기준 시나리오가 무엇이든 그 결과로 판정한다(ADR-001 한계 1번).
     */
    public Optional<ScenarioResult> findBaselineResult() {
        Optional<ScenarioResult> matched = findScenarioResult(baselineScenario);
        if (matched.isPresent()) {
            return matched;
        }
        return findScenarioResult(Scenario.AVG);
    }

    private Optional<ScenarioResult> findScenarioResult(Scenario scenario) {
        return scenarios.stream()
                .filter(result -> result.scenario() == scenario)
                .findFirst();
    }
}
