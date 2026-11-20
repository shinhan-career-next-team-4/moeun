package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * ETF 종목 하나의 월말 종가(FR-DATA-02). 3구간 산정에 쓴다. S&P500도 종목 하나({@code SP500})다.
 *
 * @param version 이 값이 속한 버전
 * @param product 그 버전의 종목 정보(화면 이름, 매매차익 세율)
 * @param monthlyCloses 월 오름차순, 빠진 달 없음(적재할 때 검증한다)
 */
public record EtfSeries(DataVersionInfo version, EtfProduct product, List<MonthlyClose> monthlyCloses) {

    public EtfSeries {
        monthlyCloses = List.copyOf(monthlyCloses);
    }

    /**
     * 한 달의 종가.
     *
     * @param month 해당 월
     * @param closeValue 월말 종가(분배금 제외, 팀 결정)
     */
    public record MonthlyClose(YearMonth month, BigDecimal closeValue) {
    }
}
