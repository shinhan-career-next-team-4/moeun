package com.moeun.backend.chat.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.CalcComparison;
import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.chat.application.ChatComparison;

/**
 * F-4 응답(계약 C12b-6). {@code /internal/calc} 성공 응답에서 calcRunId를 빼고 messageId·applicable·changedKeys를 더한
 * 모양이다. 월별 점(monthlyPoints)은 싣지 않는다. display는 calc가 만든 맵 그대로다.
 */
record ChatComparisonResponse(long messageId, boolean applicable, List<String> changedKeys, Conditions conditions,
        CalcResultView current, CalcResultView changed, CalcComparison.Diff diff, Map<String, String> display) {

    /** 변경 전·후 조건. 목표 금액은 비교의 두 결과가 각각 가지고 있다. */
    record Conditions(Side current, Side changed) {
    }

    /** 대화로 바꿀 수 있는 5개 조건의 한쪽 값(D-090·D-092). */
    record Side(long goalAmount, Long monthlySavingAmount, int savingsRatioPct, BigDecimal savingsRateInputPct,
            Integer targetPeriodMonths) {

        private static Side of(long goalAmount, CalcComparison.ConditionValues values) {
            return new Side(goalAmount, values.monthlySavingAmount(), values.savingsRatioPct(),
                    values.savingsRateInputPct(), values.targetPeriodMonths());
        }
    }

    static ChatComparisonResponse from(ChatComparison result) {
        CalcComparison comparison = result.view().comparison();
        Conditions conditions = new Conditions(
                Side.of(comparison.current().goalAmount(), comparison.conditions().current()),
                Side.of(comparison.changed().goalAmount(), comparison.conditions().changed()));
        return new ChatComparisonResponse(result.messageId(), result.applicable(), result.changedKeys(), conditions,
                CalcResultView.from(comparison.current()), CalcResultView.from(comparison.changed()),
                comparison.diff(), result.view().display());
    }
}
