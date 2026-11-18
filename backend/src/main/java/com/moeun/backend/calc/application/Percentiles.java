package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 분위와 산술평균(calc-spec 7-3).
 */
final class Percentiles {

    private Percentiles() {
    }

    /**
     * 선형 보간 분위. 오름차순 x[0 … n−1]에서 위치 h = (n − 1) × p, 값 = x[⌊h⌋] + (h − ⌊h⌋) × (x[⌊h⌋+1] − x[⌊h⌋]).
     *
     * @param sortedAscending 오름차순으로 정렬한 값(1개 이상)
     * @param fraction 분위(0 이상 1 이하, 예: 하위 10%는 0.1)
     */
    static BigDecimal percentile(BigDecimal[] sortedAscending, BigDecimal fraction) {
        BigDecimal position = BigDecimal.valueOf(sortedAscending.length - 1L).multiply(fraction);
        int lower = position.intValue();
        if (lower >= sortedAscending.length - 1) {
            return sortedAscending[sortedAscending.length - 1];
        }
        BigDecimal weight = position.subtract(BigDecimal.valueOf(lower));
        BigDecimal step = sortedAscending[lower + 1].subtract(sortedAscending[lower]);
        return sortedAscending[lower].add(step.multiply(weight, MathContext.DECIMAL128));
    }

    /** 산술평균(TBD-41). */
    static BigDecimal mean(BigDecimal[] values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        return sum.divide(BigDecimal.valueOf(values.length), MathContext.DECIMAL128);
    }
}
