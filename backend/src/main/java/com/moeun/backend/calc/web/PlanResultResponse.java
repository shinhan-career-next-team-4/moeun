package com.moeun.backend.calc.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.Assumptions;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.CurrentPlanResult;
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
 * @param undoAvailable 되돌릴 직전 계획이 있는지. 결과 화면의 '직전 계획으로 되돌리기' 버튼에 쓴다(D-095, #263)
 * @param plan 사용자가 입력한 원래 값
 * @param result 계산 결과
 * @param display 표시 문자열 맵(공유 ResultDisplayBuilder)
 */
record PlanResultResponse(long revision, long calcRunId, Scenario baselineResultScenario, boolean undoAvailable,
        PlanValues plan, ResultView result, Map<String, String> display) {

    static PlanResultResponse from(CurrentPlanResult current, Formatter formatter) {
        CalcResult result = current.result();
        Scenario baseline = result.findBaselineResult().map(ScenarioResult::scenario).orElse(null);
        return new PlanResultResponse(current.plan().revision(), current.calcRunId(), baseline,
                current.plan().canUndo(), PlanValues.from(current.plan()), ResultView.from(result, formatter),
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
            Long heldAssets, Long monthlySavingAmount, BigDecimal savingsRateInputPct, Integer savingsRatioPct,
            String etfProductCode) {

        static PlanValues from(PlanView plan) {
            return new PlanValues(plan.goalType(), plan.goalCustomName(), plan.goalAmount(),
                    plan.targetPeriodMonths(), plan.heldAssets(), plan.monthlySavingAmount(),
                    plan.savingsRateInputPct(), plan.savingsRatioPct(), plan.etfProductCode());
        }
    }

    /**
     * @param principalPoints 누적 납입 원금 월별 점(그래프 하단 막대). 엔진이 점마다 이미 담는 원금(보유 자산 + 월 저축액 ×
     *     경과 개월)이라 시나리오와 무관하게 같다. 새로 계산하지 않고 첫 시나리오의 점에서 옮긴다. 시나리오가 없으면(이미 목표 초과) 빈 목록이다
     * @param monthlyDetails 그래프에서 월을 눌렀을 때 보여 줄 월별 표시 문자열(#292). 숫자는 엔진 점 그대로이고 서식만 서버
     *     Formatter가 한다(화면은 금액을 만들지 않는다, NFR-INT-01). 시나리오가 없으면 빈 목록이다
     * @param projection 누적 영역 그래프(#315)용 시나리오별 월별 층. 응답에 있는 시나리오(GOOD·AVG·BAD 순, ETF 0%면 AVG)만
     *     담고 이미 목표 초과면 빈 객체다. 숫자는 엔진 점에서 옮기기만 한다
     */
    record ResultView(CalcStatus status, long goalAmount, YearMonth calcStartMonth, YearMonth targetMonth,
            List<ScenarioView> scenarios, List<PrincipalPointView> principalPoints,
            List<MonthDetailView> monthlyDetails, Map<Scenario, List<ProjectionPointView>> projection,
            Assumptions assumptions) {

        static ResultView from(CalcResult result, Formatter formatter) {
            return new ResultView(result.status(), result.goalAmount(), result.calcStartMonth(), result.targetMonth(),
                    result.scenarios().stream().map(ScenarioView::from).toList(), principalPointsOf(result),
                    MonthDetailView.listOf(result, formatter), ProjectionPointView.mapOf(result),
                    result.assumptions());
        }

        private static List<PrincipalPointView> principalPointsOf(CalcResult result) {
            return result.scenarios().stream().findFirst()
                    .map(scenario -> scenario.monthlyPoints().stream().map(PrincipalPointView::from).toList())
                    .orElseGet(List::of);
        }
    }

    /**
     * 월 하나의 표시 문자열. {@code afterTaxAsset}은 응답에 있는 시나리오(GOOD·AVG·BAD 순, ETF 0%면 AVG)만 담는다.
     */
    record MonthDetailView(YearMonth month, String label, String principal, Map<Scenario, String> afterTaxAsset) {

        static List<MonthDetailView> listOf(CalcResult result, Formatter formatter) {
            List<ScenarioResult> scenarios = result.scenarios();
            if (scenarios.isEmpty()) {
                return List.of();
            }
            List<MonthDetailView> details = new ArrayList<>();
            List<MonthlyPoint> first = scenarios.get(0).monthlyPoints();
            for (int k = 0; k < first.size(); k++) {
                MonthlyPoint point = first.get(k);
                Map<Scenario, String> assets = new LinkedHashMap<>();
                for (ScenarioResult scenario : scenarios) {
                    long asset = scenario.monthlyPoints().get(k).afterTaxAsset();
                    assets.put(scenario.scenario(), formatter.formatAmount(asset));
                }
                details.add(new MonthDetailView(point.month(), formatter.formatMonth(point.month()),
                        formatter.formatAmount(point.principal()), assets));
            }
            return details;
        }
    }

    /**
     * 영역 그래프의 한 달. {@code month}는 1부터 시작하는 경과 개월이라 출발점(k=0)은 담지 않는다.
     * total = principal + savingsInterest + etfGain, profit = savingsInterest + etfGain. etfGain은 음수일 수 있다.
     */
    record ProjectionPointView(int month, long principal, long savingsInterest, long etfGain, long profit,
            long total) {

        static Map<Scenario, List<ProjectionPointView>> mapOf(CalcResult result) {
            Map<Scenario, List<ProjectionPointView>> projection = new LinkedHashMap<>();
            for (ScenarioResult scenario : result.scenarios()) {
                List<MonthlyPoint> points = scenario.monthlyPoints();
                List<ProjectionPointView> views = new ArrayList<>(points.size());
                for (int k = 1; k < points.size(); k++) {
                    views.add(from(k, points.get(k)));
                }
                projection.put(scenario.scenario(), views);
            }
            return projection;
        }

        private static ProjectionPointView from(int month, MonthlyPoint point) {
            long etfGain = point.etfGain();
            return new ProjectionPointView(month, point.principal(), point.savingsInterest(), etfGain,
                    point.savingsInterest() + etfGain, point.afterTaxAsset());
        }
    }

    /** 누적 납입 원금 점. month·principal만 담는다. */
    record PrincipalPointView(YearMonth month, long principal) {

        static PrincipalPointView from(MonthlyPoint point) {
            return new PrincipalPointView(point.month(), point.principal());
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
