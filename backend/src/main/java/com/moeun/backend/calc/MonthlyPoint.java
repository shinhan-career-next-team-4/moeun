package com.moeun.backend.calc;

import java.time.YearMonth;
import java.util.Objects;

/**
 * 월별 예상 자산 한 점(FR-RES-04 꺾은선 그래프).
 *
 * @param month 해당 월
 * @param principal 그 달까지 넣은 원금(보유 자산 포함)
 * @param afterTaxAsset 그 달에 모두 정리한다고 볼 때의 세후 예상 자산
 */
public record MonthlyPoint(YearMonth month, long principal, long afterTaxAsset) {

    public MonthlyPoint {
        Objects.requireNonNull(month, "month");
    }
}
