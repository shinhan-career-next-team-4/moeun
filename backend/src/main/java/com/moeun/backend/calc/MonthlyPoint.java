package com.moeun.backend.calc;

import java.time.YearMonth;
import java.util.Objects;

/**
 * 월별 예상 자산 한 점(FR-RES-04 꺾은선 그래프).
 *
 * @param month 해당 월
 * @param principal 그 달까지 넣은 원금(보유 자산 포함)
 * @param afterTaxAsset 그 달에 모두 정리한다고 볼 때의 세후 예상 자산
 * @param savingsInterest 그 중 적금 몫의 세후 이자(적금 평가액 − 적금에 넣은 원금). ETF 수익은
 *     afterTaxAsset − principal − savingsInterest로 구한다(#315, 영역 그래프의 층 나누기)
 */
public record MonthlyPoint(YearMonth month, long principal, long afterTaxAsset, long savingsInterest) {

    public MonthlyPoint {
        Objects.requireNonNull(month, "month");
    }

    /** 적금 이자를 나누지 않는 점(0으로 둔다). */
    public MonthlyPoint(YearMonth month, long principal, long afterTaxAsset) {
        this(month, principal, afterTaxAsset, 0L);
    }

    /** ETF 몫의 세후 손익(음수 가능). 합치면 afterTaxAsset이 되도록 나머지로 구한다. */
    public long etfGain() {
        return afterTaxAsset - principal - savingsInterest;
    }
}
