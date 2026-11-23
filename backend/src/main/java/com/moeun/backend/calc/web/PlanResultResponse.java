package com.moeun.backend.calc.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.Assumptions;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.CurrentPlanResult;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.Formatter;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanView;

/**
 * 화면 전용 현재 계획·결과 응답(계약 C12a-6-1 PlanView). P-3 입력 저장과 P-4 현재 계획 조회가 같은 모양을 쓴다.
 * <p>화면이 쓰지 않는 값은 내보내지 않는다: raw 조건, {@code annualReturnPct}, {@code requiredMonthlySaving},
 * {@code dataVersions}, 기준 시나리오, 계획의 시작월, 계획 버전 id. 문구 키 목록도 넣지 않는다(프론트가 문구표에서 고름).
 * null 값도 키를 지우지 않고 그대로 둔다.
 *
 * @param revision 계획 revision. 프론트가 다음 저장·적용의 expectedRevision으로 쓴다
 * @param calcRunId 이 결과의 calc_run(PLAN) id. 같은 revision 동안 같다
 * @param baselineResultScenario 결과 요약에 쓸 시나리오(대체 규칙 포함). 이미 목표 초과면 null
 * @param plan 사용자가 입력한 원래 값
 * @param result 계산 결과
 * @param display 표시 문자열 맵(공유 ResultDisplayBuilder)
 */
record PlanResultResponse(long revision, long calcRunId, Scenario baselineResultScenario, PlanValues plan,
        ResultView result, Map<String, String> display) {

    static PlanResultResponse from(CurrentPlanResult current, Formatter formatter) {
        CalcResult result = current.result();
        Scenario baseline = result.findBaselineResult().map(ScenarioResult::scenario).orElse(null);
        return new PlanResultResponse(current.plan().revision(), current.calcRunId(), baseline,
                PlanValues.from(current.plan()), ResultView.from(result),
                withGapBadges(current.display(), result, formatter));
    }

    /**
     * 화면 전용 배지 문자열 {@code <S>.gapBadge}(#224)를 더한다. 공유 display(planContext·ai-server 계약)는 건드리지 않으려고
     * 이 응답에서만 붙인다.
     */
    private static Map<String, String> withGapBadges(Map<String, String> shared, CalcResult result,
            Formatter formatter) {
        Map<String, String> display = new LinkedHashMap<>(shared);
        for (ScenarioResult scenario : result.scenarios()) {
            display.put(scenario.scenario() + ".gapBadge",
                    formatter.formatGapBadge(scenario.gapToGoal(), scenario.isAchieved()));
        }
        return display;
    }

    /** 사용자가 입력한 원래 값(원 단위, nullable 그대로). 기본금리·기본 비중을 채우기 전 값이다. */
    record PlanValues(GoalType goalType, String goalCustomName, Long goalAmount, Integer targetPeriodMonths,
            Long heldAssets, Long monthlySavingAmount, BigDecimal savingsRateInputPct, Integer savingsRatioPct) {

        static PlanValues from(PlanView plan) {
            return new PlanValues(plan.goalType(), plan.goalCustomName(), plan.goalAmount(),
                    plan.targetPeriodMonths(), plan.heldAssets(), plan.monthlySavingAmount(),
                    plan.savingsRateInputPct(), plan.savingsRatioPct());
        }
    }

    record ResultView(CalcStatus status, long goalAmount, YearMonth calcStartMonth, YearMonth targetMonth,
            List<ScenarioView> scenarios, Assumptions assumptions) {

        static ResultView from(CalcResult result) {
            return new ResultView(result.status(), result.goalAmount(), result.calcStartMonth(), result.targetMonth(),
                    result.scenarios().stream().map(ScenarioView::from).toList(), result.assumptions());
        }
    }

    /** 시나리오 하나. 엔진의 월별 점을 그대로 옮기고 붙이거나 빼지 않는다(C12a-6-2). */
    record ScenarioView(Scenario scenario, long afterTaxAsset, long principal, long afterTaxGain, long gapToGoal,
            boolean isAchieved, YearMonth goalReachMonth, List<PointView> monthlyPoints) {

        static ScenarioView from(ScenarioResult scenario) {
            return new ScenarioView(scenario.scenario(), scenario.afterTaxAsset(), scenario.principal(),
                    scenario.afterTaxGain(), scenario.gapToGoal(), scenario.isAchieved(), scenario.goalReachMonth(),
                    scenario.monthlyPoints().stream().map(PointView::from).toList());
        }
    }

    /** 그래프 점. 화면은 원금을 쓰지 않으므로 month·afterTaxAsset만 담는다. */
    record PointView(YearMonth month, long afterTaxAsset) {

        static PointView from(MonthlyPoint point) {
            return new PointView(point.month(), point.afterTaxAsset());
        }
    }
}
