package com.moeun.backend.refdata.application;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.moeun.backend.refdata.EtfSeries.MonthlyClose;
import com.moeun.backend.refdata.application.CsvRows.Row;

/** ETF 종목 월말 종가 CSV(ym,close_value)를 읽고 검증한다. 종목마다 같은 형식이다(S&P500 포함). */
final class EtfPriceCsvParser {

    /**
     * 최소 개월 수. 120개월 구간 하나의 수익률을 내려면 시작과 끝, 121개 점이 필요하다.
     * 임시값, 팀 확정 필요(TBD-01 관측 구간).
     */
    static final int MIN_MONTH_COUNT = 121;

    private static final List<String> HEADER = List.of("ym", "close_value");
    private static final int MAX_INTEGER_DIGITS = 10;
    private static final int MAX_SCALE = 4;

    private EtfPriceCsvParser() {
    }

    /** 월 오름차순으로 돌려준다. */
    static List<MonthlyClose> parse(InputStream input) {
        List<MonthlyClose> monthlyCloses = new ArrayList<>();
        Set<YearMonth> seenMonths = new HashSet<>();
        for (Row row : CsvRows.read(input, HEADER)) {
            YearMonth month = row.readMonth(0);
            BigDecimal closeValue = row.readNumber(1, MAX_INTEGER_DIGITS, MAX_SCALE);
            if (closeValue.signum() <= 0) {
                throw ReferenceDataLoadException.atLine(row.lineNumber(), "close_value must be positive");
            }
            if (!seenMonths.add(month)) {
                throw ReferenceDataLoadException.atLine(row.lineNumber(), "duplicate month " + month);
            }
            monthlyCloses.add(new MonthlyClose(month, closeValue));
        }
        monthlyCloses.sort(Comparator.comparing(MonthlyClose::month));
        requireNoMissingMonth(monthlyCloses);
        if (monthlyCloses.size() < MIN_MONTH_COUNT) {
            throw new ReferenceDataLoadException(
                    "at least " + MIN_MONTH_COUNT + " months required but found " + monthlyCloses.size());
        }
        return List.copyOf(monthlyCloses);
    }

    // 빠진 달이 있으면 한 달씩 미는 구간 수익률이 틀어지므로 받지 않는다
    private static void requireNoMissingMonth(List<MonthlyClose> sortedCloses) {
        for (int index = 1; index < sortedCloses.size(); index++) {
            YearMonth expected = sortedCloses.get(index - 1).month().plusMonths(1);
            if (!sortedCloses.get(index).month().equals(expected)) {
                throw new ReferenceDataLoadException("missing month " + expected);
            }
        }
    }
}
