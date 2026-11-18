package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * 매월 초 같은 금액으로 ETF를 사는 한 경로(calc-spec 6절).
 * <p>평가액은 V(k) = (V(k−1) + Me) × (1 + r(k))로 이어지고, 점마다 "그 달에 모두 판다면"의 세후 값을 낸다.
 * 다음 달로 넘기는 값은 세금을 빼지 않은 V(k)다. 곱셈이 거듭되며 자릿수가 늘지 않게 34자리로 자른다.
 */
final class EtfPath {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final BigDecimal monthlyAmount;
    private final BigDecimal taxRatio;

    /**
     * @param monthlyAmount 매월 ETF에 넣는 금액(Me, 원)
     * @param taxRatePct 매매차익에 매기는 세율(%)
     */
    EtfPath(BigDecimal monthlyAmount, BigDecimal taxRatePct) {
        this.monthlyAmount = monthlyAmount;
        this.taxRatio = taxRatePct.divide(PERCENT, MathContext.DECIMAL128);
    }

    /** 이번 달 초에 Me를 사고 한 달 수익률을 적용한 평가액(세전). */
    BigDecimal grow(BigDecimal previousValue, BigDecimal monthlyReturn) {
        return previousValue.add(monthlyAmount).multiply(BigDecimal.ONE.add(monthlyReturn), MathContext.DECIMAL128);
    }

    /**
     * 평가액을 지금 모두 판다고 볼 때의 세후 값. 차익이 0 이하면 세금이 없다(TBD-02).
     *
     * @param value 세전 평가액 V(k)
     * @param paymentCount 지금까지 산 횟수 k
     */
    BigDecimal afterTax(BigDecimal value, int paymentCount) {
        BigDecimal gain = value.subtract(monthlyAmount.multiply(BigDecimal.valueOf(paymentCount)));
        if (gain.signum() <= 0) {
            return value;
        }
        return value.subtract(gain.multiply(taxRatio, MathContext.DECIMAL128));
    }

    /** 수익률 순서 하나로 k = 0 … N의 세후 값을 낸다. 반올림하지 않은 값이다. */
    List<BigDecimal> afterTaxValues(List<BigDecimal> monthlyReturns) {
        List<BigDecimal> values = new ArrayList<>(monthlyReturns.size() + 1);
        values.add(BigDecimal.ZERO);
        BigDecimal value = BigDecimal.ZERO;
        for (int index = 0; index < monthlyReturns.size(); index++) {
            value = grow(value, monthlyReturns.get(index));
            values.add(afterTax(value, index + 1));
        }
        return values;
    }
}
