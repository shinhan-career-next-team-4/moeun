package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import com.moeun.backend.refdata.Sp500Series.MonthlyClose;

/**
 * 월말 종가에서 한 달 수익률을 만든다(calc-spec 7-1): r(t) = 종가(t) ÷ 종가(t−1) − 1.
 */
final class MonthlyReturns {

    private MonthlyReturns() {
    }

    /**
     * 최근 관측 기간의 한 달 수익률. 오래된 달이 0번이다.
     *
     * @param closes 월 오름차순 종가(빠진 달 없음, 적재할 때 검증됨)
     * @param observationMonths 관측 기간(개월). 종가는 이보다 1개 더 필요하다
     * @throws IllegalStateException 종가가 관측 기간 + 1개보다 적을 때
     */
    static List<BigDecimal> fromCloses(List<MonthlyClose> closes, int observationMonths) {
        int requiredCloses = observationMonths + 1;
        if (closes.size() < requiredCloses) {
            throw new IllegalStateException("S&P500 needs at least " + requiredCloses + " monthly closes but has "
                    + closes.size());
        }
        List<MonthlyClose> window = closes.subList(closes.size() - requiredCloses, closes.size());
        List<BigDecimal> returns = new ArrayList<>(observationMonths);
        for (int index = 1; index < window.size(); index++) {
            BigDecimal previous = window.get(index - 1).closeValue();
            BigDecimal current = window.get(index).closeValue();
            returns.add(current.divide(previous, MathContext.DECIMAL128).subtract(BigDecimal.ONE));
        }
        return List.copyOf(returns);
    }
}
