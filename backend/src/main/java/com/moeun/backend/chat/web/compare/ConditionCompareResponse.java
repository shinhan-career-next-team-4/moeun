package com.moeun.backend.chat.web.compare;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.CalcComparison;
import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.calc.ChatComparisonView;

/**
 * 조건 변경 비교 응답(FR-CMP-02). 대화 비교 조회(F-4) 응답에서 messageId만 뺀 같은 모양이다(프론트가 같은 코드로 읽는다).
 * 3구간(GOOD·AVG·BAD, ETF 비중이 0%면 AVG 하나)은 current·changed의 scenarios에 있고 월별 점은 싣지 않는다. display는
 * calc가 만든 맵 그대로다.
 *
 * @param applicable 현재 계획을 기준으로 방금 계산한 비교인지. 저장된 비교가 아니라 오래돼 어긋날 일이 없어 늘 true다
 * @param changedKeys 바꾼 조건의 키(값이 null이 아닌 키, 요청 순서)
 */
record ConditionCompareResponse(boolean applicable, List<String> changedKeys, Conditions conditions,
        CalcResultView current, CalcResultView changed, CalcComparison.Diff diff, Map<String, String> display) {

    /** 변경 전·후 조건. */
    record Conditions(Side current, Side changed) {
    }

    /** 비교로 바꿀 수 있는 5개 조건의 한쪽 값(D-090·D-092). */
    record Side(long goalAmount, Long monthlySavingAmount, int savingsRatioPct, BigDecimal savingsRateInputPct,
            Integer targetPeriodMonths) {

        private static Side of(long goalAmount, CalcComparison.ConditionValues values) {
            return new Side(goalAmount, values.monthlySavingAmount(), values.savingsRatioPct(),
                    values.savingsRateInputPct(), values.targetPeriodMonths());
        }
    }

    static ConditionCompareResponse from(ChatComparisonView view, List<String> changedKeys) {
        CalcComparison comparison = view.comparison();
        Conditions conditions = new Conditions(
                Side.of(comparison.current().goalAmount(), comparison.conditions().current()),
                Side.of(comparison.changed().goalAmount(), comparison.conditions().changed()));
        return new ConditionCompareResponse(true, changedKeys, conditions, CalcResultView.from(comparison.current()),
                CalcResultView.from(comparison.changed()), comparison.diff(), view.display());
    }
}
