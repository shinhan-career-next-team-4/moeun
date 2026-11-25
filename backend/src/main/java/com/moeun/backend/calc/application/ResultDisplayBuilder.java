package com.moeun.backend.calc.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.Assumptions;
import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.ScenarioResult;

/**
 * 계산 결과를 display 맵(키 → 표시 문자열)으로 만드는 빌더 하나(계약 internal-calc C2-6, planContext C1a-6).
 * <p>결과 화면(#30), {@code /internal/calc}(#32), planContext(#78)가 모두 이 빌더를 쓴다. 같은 값을 세 곳에서
 * 따로 만들면 "화면 숫자 = AI 숫자 글자까지"(PR-04)를 지킬 수 없어서다. 형식은 {@link Formatter}가 정한다.
 * <p>키 규칙:
 * <ul>
 *     <li>{@code goalAmount}: 접두어 없음(비교에서도 두 계산의 목표가 같다)</li>
 *     <li>결과 접두어(""·"current."·"changed.") + {@code targetMonth},
 *     {@code <S>.afterTaxAsset·principal·afterTaxGain·gapToGoal·goalReachMonth}</li>
 *     <li>조건 접두어("conditions."·"conditions.current."·"conditions.changed.") + 월 저축액·기간·비중·금리</li>
 *     <li>결과 접두어가 ""일 때(결과 화면·planContext)만 C1a-6이 C2-6에 더한 키
 *     {@code calcStartMonth}, 조건의 {@code heldAssets}, {@code assumptions.*}. 비교({@code /internal/calc})에서는
 *     만들지 않는다. C2-6이 가정 설명은 planContext에서 다룬다고 정했고, 비교 답변이 세율·기준월을 인용하지 않게 하려는 것이다</li>
 * </ul>
 * 응답에 있는 시나리오만 키를 만든다(ETF 0%면 AVG, 이미 목표 초과면 없음).
 * {@code <S>.goalReachMonth}는 달성했고 도달 시점이 있을 때만 만든다. 미달인데 중간에 목표에 닿았던 시나리오에
 * 도달 시점을 내보내면 "미달인데 도달"이라는 모순된 설명이 나오기 때문이다(C1a-6 v3).
 */
@Component
public class ResultDisplayBuilder {

    private final Formatter formatter;

    public ResultDisplayBuilder(Formatter formatter) {
        this.formatter = formatter;
    }

    /**
     * @param input 계산에 쓴 조건(조건 키의 값)
     * @param result 계산 결과
     * @param resultPrefix 결과 키 접두어: {@code ""}, {@code "current."}, {@code "changed."}
     * @param conditionPrefix 조건 키 접두어: {@code "conditions."}, {@code "conditions.current."},
     *     {@code "conditions.changed."}
     * @return 키를 넣은 순서가 유지되는 맵
     */
    public Map<String, String> build(CalcInput input, CalcResult result, String resultPrefix, String conditionPrefix) {
        return build(input, result, resultPrefix, conditionPrefix, false, true);
    }

    /**
     * @param includeSideGoalAmount {@code conditions.<side>.goalAmount} 포함 여부(목표 금액이 바뀐 비교)
     * @param includeUnprefixedGoalAmount 무접두어 {@code goalAmount} 포함 여부(단일 결과·목표 미변경 비교)
     */
    public Map<String, String> build(CalcInput input, CalcResult result, String resultPrefix, String conditionPrefix,
            boolean includeSideGoalAmount, boolean includeUnprefixedGoalAmount) {
        boolean isSingleResult = resultPrefix.isEmpty();
        Map<String, String> display = new LinkedHashMap<>();
        if (includeUnprefixedGoalAmount) {
            display.put("goalAmount", formatter.formatAmount(result.goalAmount()));
        }
        if (isSingleResult) {
            display.put("calcStartMonth", formatter.formatMonth(result.calcStartMonth()));
        }
        if (result.targetMonth() != null) {
            display.put(resultPrefix + "targetMonth", formatter.formatMonth(result.targetMonth()));
        }
        for (ScenarioResult scenario : result.scenarios()) {
            putScenario(display, resultPrefix + scenario.scenario() + ".", scenario);
        }
        putConditions(display, conditionPrefix, input, result, isSingleResult, includeSideGoalAmount);
        if (isSingleResult) {
            putAssumptions(display, result.assumptions());
        }
        return display;
    }

    private void putScenario(Map<String, String> display, String prefix, ScenarioResult scenario) {
        display.put(prefix + "afterTaxAsset", formatter.formatAmount(scenario.afterTaxAsset()));
        display.put(prefix + "principal", formatter.formatAmount(scenario.principal()));
        display.put(prefix + "afterTaxGain", formatter.formatAmount(scenario.afterTaxGain()));
        display.put(prefix + "gapToGoal", formatter.formatGapToGoal(scenario.gapToGoal(), scenario.isAchieved()));
        if (scenario.isAchieved() && scenario.goalReachMonth() != null) {
            display.put(prefix + "goalReachMonth", formatter.formatMonth(scenario.goalReachMonth()));
        }
    }

    private void putConditions(Map<String, String> display, String prefix, CalcInput input,
            CalcResult result, boolean isSingleResult, boolean includeSideGoalAmount) {
        if (includeSideGoalAmount) {
            display.put(prefix + "goalAmount", formatter.formatAmount(result.goalAmount()));
        }
        if (isSingleResult || result.status() == CalcStatus.ALREADY_EXCEEDED) {
            display.put(prefix + "heldAssets", formatter.formatAmount(input.heldAssets()));
        }
        if (input.monthlySavingAmount() != null) {
            display.put(prefix + "monthlySavingAmount", formatter.formatAmount(input.monthlySavingAmount()));
        }
        if (input.targetPeriodMonths() != null) {
            display.put(prefix + "targetPeriodMonths", formatter.formatPeriod(input.targetPeriodMonths()));
        }
        display.put(prefix + "savingsRatioPct", formatter.formatRatio(input.savingsRatioPct()));
        display.put(prefix + "savingsRateInputPct", formatter.formatSavingsRate(input.savingsRatePct()));
    }

    private void putAssumptions(Map<String, String> display, Assumptions assumptions) {
        String prefix = "assumptions.";
        if (assumptions.savingsRateBaseMonth() != null) {
            display.put(prefix + "savingsRateBaseMonth", formatter.formatMonth(assumptions.savingsRateBaseMonth()));
        }
        if (assumptions.sp500BaseDate() != null) {
            display.put(prefix + "sp500BaseDate", formatter.formatMonth(assumptions.sp500BaseDate()));
        }
        display.put(prefix + "taxRatePct", formatter.formatTaxRate(assumptions.taxRatePct()));
        // ETF가 들어간 계산만 종목 이름과 종목 세율이 있다(#150)
        if (assumptions.etfProductName() != null) {
            display.put(prefix + "etfProductName", assumptions.etfProductName());
        }
        if (assumptions.etfGainTaxRatePct() != null) {
            display.put(prefix + "etfGainTaxRatePct", formatter.formatEtfGainTaxRate(assumptions.etfGainTaxRatePct()));
        }
    }
}
