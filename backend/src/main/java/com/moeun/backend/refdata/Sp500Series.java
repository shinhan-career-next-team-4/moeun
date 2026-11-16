package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * S&P500 월별 종가(FR-DATA-02). 3구간 산정에 쓴다.
 *
 * @param version 이 값이 속한 버전
 * @param monthlyCloses 월 오름차순, 빠진 달 없음(적재할 때 검증한다)
 */
public record Sp500Series(DataVersionInfo version, List<MonthlyClose> monthlyCloses) {

    public Sp500Series {
        monthlyCloses = List.copyOf(monthlyCloses);
    }

    /**
     * 한 달의 종가.
     *
     * @param month 해당 월
     * @param closeValue 지수 종가(분배금 제외, TBD-02)
     */
    public record MonthlyClose(YearMonth month, BigDecimal closeValue) {
    }
}
