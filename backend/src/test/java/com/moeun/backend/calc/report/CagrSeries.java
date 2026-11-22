package com.moeun.backend.calc.report;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.EtfSeries.MonthlyClose;

/**
 * 비교용 방식 A(CAGR, calc-spec 7-4)를 엔진으로 계산하기 위한 시계열(#162).
 * <p>관측 기간의 첫 종가와 마지막 종가로 월 수익률 g = (마지막 ÷ 첫)^(1/n) − 1을 구하고, 같은 달들에 매달 1 + g씩 오르는
 * 종가를 만든다. 수익률이 모두 같으면 부트스트랩이 어떤 순서로 뽑아도 경로가 같아 세 구간이 하나로 모이므로(골든 G8),
 * 엔진을 고치지 않고 방식 A 값을 얻는다. 관측 창은 엔진(MonthlyReturns)과 같은 최근 n + 1개 종가다.
 */
final class CagrSeries {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    /** double 근삿값(약 16자리)에서 시작하면 뉴턴법 두세 번이면 34자리가 맞는다. */
    private static final int NEWTON_STEPS = 3;

    private CagrSeries() {
    }

    /**
     * 방식 A의 월 수익률 g.
     *
     * @throws IllegalStateException 종가가 관측 기간 + 1개보다 적을 때(엔진과 같은 조건)
     */
    static BigDecimal calculateMonthlyRate(EtfSeries observed, int observationMonths) {
        List<MonthlyClose> window = selectWindow(observed, observationMonths);
        BigDecimal ratio = window.get(window.size() - 1).closeValue()
                .divide(window.get(0).closeValue(), PRECISION);
        return calculateNthRoot(ratio, observationMonths).subtract(BigDecimal.ONE);
    }

    /** 관측 창의 달·첫 종가·종목·데이터 버전은 그대로 두고 종가만 매달 1 + g씩 오르게 바꾼 시계열. */
    static EtfSeries of(EtfSeries observed, int observationMonths) {
        List<MonthlyClose> window = selectWindow(observed, observationMonths);
        BigDecimal growth = BigDecimal.ONE.add(calculateMonthlyRate(observed, observationMonths));
        List<MonthlyClose> closes = new ArrayList<>(window.size());
        BigDecimal close = window.get(0).closeValue();
        for (MonthlyClose original : window) {
            closes.add(new MonthlyClose(original.month(), close));
            close = close.multiply(growth, PRECISION);
        }
        return new EtfSeries(observed.version(), observed.product(), closes);
    }

    private static List<MonthlyClose> selectWindow(EtfSeries observed, int observationMonths) {
        List<MonthlyClose> closes = observed.monthlyCloses();
        int requiredCloses = observationMonths + 1;
        if (closes.size() < requiredCloses) {
            throw new IllegalStateException("CAGR needs at least " + requiredCloses + " monthly closes but has "
                    + closes.size());
        }
        return closes.subList(closes.size() - requiredCloses, closes.size());
    }

    /** value^(1/degree). x ← x − (x^n − v) ÷ (n × x^(n−1)). */
    private static BigDecimal calculateNthRoot(BigDecimal value, int degree) {
        BigDecimal root = new BigDecimal(Math.pow(value.doubleValue(), 1.0 / degree), PRECISION);
        BigDecimal exponent = BigDecimal.valueOf(degree);
        for (int step = 0; step < NEWTON_STEPS; step++) {
            BigDecimal power = root.pow(degree - 1, PRECISION);
            BigDecimal excess = power.multiply(root, PRECISION).subtract(value, PRECISION);
            root = root.subtract(excess.divide(exponent.multiply(power, PRECISION), PRECISION), PRECISION);
        }
        return root;
    }
}
