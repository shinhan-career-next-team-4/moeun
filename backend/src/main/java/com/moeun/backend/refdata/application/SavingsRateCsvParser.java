package com.moeun.backend.refdata.application;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.moeun.backend.refdata.application.CsvRows.Row;

/** 적금 평균금리 CSV(base_month,avg_rate_pct)를 읽고 검증한다. 버전당 한 건이다. */
final class SavingsRateCsvParser {

    private static final List<String> HEADER = List.of("base_month", "avg_rate_pct");
    private static final int MAX_INTEGER_DIGITS = 3;
    private static final int MAX_SCALE = 3;

    private SavingsRateCsvParser() {
    }

    static SavingsRate parse(InputStream input) {
        List<Row> rows = CsvRows.read(input, HEADER);
        if (rows.size() != 1) {
            throw new ReferenceDataLoadException("exactly one rate row required but found " + rows.size());
        }
        Row row = rows.get(0);
        YearMonth baseMonth = row.readMonth(0);
        BigDecimal avgRatePct = row.readNumber(1, MAX_INTEGER_DIGITS, MAX_SCALE);
        if (avgRatePct.signum() < 0) {
            throw ReferenceDataLoadException.atLine(row.lineNumber(), "avg_rate_pct must not be negative");
        }
        return new SavingsRate(baseMonth, avgRatePct);
    }

    /**
     * 파일에서 읽은 평균금리.
     *
     * @param baseMonth 기준월
     * @param avgRatePct 평균금리(연, 세전, %)
     */
    record SavingsRate(YearMonth baseMonth, BigDecimal avgRatePct) {
    }
}
