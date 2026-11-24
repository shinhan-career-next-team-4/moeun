package com.moeun.backend.calc.web.alternative;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.AlternativeView;
import com.moeun.backend.calc.CalcComparison;
import com.moeun.backend.calc.CalcResultView;

/**
 * 대안 응답. 대안 없음(달성·초과 계획)이면 {@code hasAlternatives=false}에 빈 목록이다.
 * 카드는 {@code monthlySavingAmount}·{@code targetPeriodMonths}·{@code savingsRatioPct} 순서이고, 바꿀 값이 없는 카드는
 * {@code changes}가 비고 나머지가 null인 빈 카드다. 월별 점은 싣지 않는다.
 */
record AlternativeResponse(boolean hasAlternatives, List<Card> alternatives) {

    /**
     * @param key 바꾼 조건
     * @param changes {@code {key: 바꾼 값}}. '비교하기'가 비교 영역에 채울 조건. 빈 카드면 빈 맵
     * @param conditions 두 계산에 쓴 조건. 바꾼 조건 하나만 current와 다르다
     * @param display 표시 문자열 맵(calc가 만든 그대로)
     * @param unreachable 허용 범위 안에서 목표를 달성하는 값을 찾지 못했다(달성이 어려움, #343). 이때 changes는 비어 있다
     */
    record Card(String key, Map<String, Number> changes, Conditions conditions, CalcResultView current,
            CalcResultView changed, CalcComparison.Diff diff, Map<String, String> display,
            boolean unreachable) {

        static Card from(AlternativeView.Alternative alternative) {
            String key = alternative.key().apiName();
            CalcComparison comparison = alternative.comparison();
            if (comparison == null) {
                return new Card(key, Map.of(), null, null, null, null, Map.of(), alternative.unreachable());
            }
            Conditions conditions = new Conditions(
                    Side.of(comparison.current().goalAmount(), comparison.conditions().current()),
                    Side.of(comparison.changed().goalAmount(), comparison.conditions().changed()));
            return new Card(key, Map.of(key, alternative.changedValue()), conditions,
                    CalcResultView.from(comparison.current()), CalcResultView.from(comparison.changed()),
                    comparison.diff(), alternative.display(), false);
        }
    }

    /** 변경 전·후 조건. */
    record Conditions(Side current, Side changed) {
    }

    /** 바꿀 수 있는 5개 조건의 한쪽 값. */
    record Side(long goalAmount, Long monthlySavingAmount, int savingsRatioPct, BigDecimal savingsRateInputPct,
            Integer targetPeriodMonths) {

        static Side of(long goalAmount, CalcComparison.ConditionValues values) {
            return new Side(goalAmount, values.monthlySavingAmount(), values.savingsRatioPct(),
                    values.savingsRateInputPct(), values.targetPeriodMonths());
        }
    }

    static AlternativeResponse from(AlternativeView view) {
        return new AlternativeResponse(view.needed(), view.alternatives().stream().map(Card::from).toList());
    }
}
