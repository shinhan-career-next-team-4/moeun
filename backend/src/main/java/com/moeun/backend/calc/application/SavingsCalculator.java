package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 월 적립식 적금의 단리 평가액(calc-spec 5절).
 * <p>매월 초 같은 금액을 넣고, 회차 j는 k번째 시점까지 k − j + 1개월 이자를 받는다.
 * 그래서 k번째 시점까지의 세전 이자는 한 달 이자 단가 u의 1 + 2 + … + k배, 즉 u × k(k+1)/2다.
 * 계산 중에는 반올림하지 않는다. 원 단위 반올림은 결과를 만들 때 한 번만 한다(calc-spec 2절).
 */
final class SavingsCalculator {

    private static final BigDecimal MONTHS_PER_YEAR_PERCENT = BigDecimal.valueOf(1200);
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final BigDecimal monthlyDeposit;
    private final BigDecimal monthlyInterestUnit;
    private final BigDecimal afterTaxRatio;

    /**
     * @param monthlyDeposit 매월 적금에 넣는 금액(Ms, 원)
     * @param annualRatePct 적금 금리(연, 세전, %)
     * @param taxRatePct 이자에 매기는 세율(%)
     */
    SavingsCalculator(BigDecimal monthlyDeposit, BigDecimal annualRatePct, BigDecimal taxRatePct) {
        this.monthlyDeposit = monthlyDeposit;
        // u = Ms × 연금리 ÷ 100 ÷ 12. 1200으로 한 번에 나누면 끝나지 않는 소수가 생길 수 있어 34자리로 자른다
        this.monthlyInterestUnit = monthlyDeposit.multiply(annualRatePct)
                .divide(MONTHS_PER_YEAR_PERCENT, MathContext.DECIMAL128);
        this.afterTaxRatio = BigDecimal.ONE.subtract(taxRatePct.divide(PERCENT, MathContext.DECIMAL128));
    }

    /** 한 회차가 한 달 동안 받는 세전 이자(u). */
    BigDecimal monthlyInterestUnit() {
        return monthlyInterestUnit;
    }

    /**
     * k번 납입하고 k개월이 지난 시점의 세후 적금 평가액. 반올림하지 않은 값이다.
     *
     * @param paymentCount 납입 횟수 k(0 이상)
     */
    BigDecimal valueAt(int paymentCount) {
        BigDecimal paidIn = monthlyDeposit.multiply(BigDecimal.valueOf(paymentCount));
        BigDecimal interestMonths = BigDecimal.valueOf((long)paymentCount * (paymentCount + 1)).divide(TWO);
        BigDecimal afterTaxInterest = monthlyInterestUnit.multiply(interestMonths).multiply(afterTaxRatio);
        return paidIn.add(afterTaxInterest);
    }
}
