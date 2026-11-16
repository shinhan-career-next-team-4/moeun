package com.moeun.backend.refdata.application;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

/** 테스트에서 CSV 내용을 만들어 주는 도우미. */
final class CsvFixtures {

    private CsvFixtures() {
    }

    static InputStream toStream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** startMonth부터 monthCount개월의 가짜 종가 CSV. 값은 100, 101, 102...로 늘어난다. */
    static String createSp500Csv(YearMonth startMonth, int monthCount) {
        StringBuilder csv = new StringBuilder("ym,close_value\n");
        for (int index = 0; index < monthCount; index++) {
            csv.append(startMonth.plusMonths(index)).append(',').append(100 + index).append('\n');
        }
        return csv.toString();
    }
}
