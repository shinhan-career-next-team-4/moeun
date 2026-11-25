package com.moeun.backend.refdata.application;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.application.CsvRows.Row;

/** 계산 파라미터 CSV(param_key,param_value)를 읽고 줄 단위로 검증한다. */
final class CalcParamCsvParser {

    private static final List<String> HEADER = List.of("param_key", "param_value");
    private static final int MAX_INTEGER_DIGITS = 8;
    private static final int MAX_SCALE = 6;

    private CalcParamCsvParser() {
    }

    static Map<CalcParamKey, BigDecimal> parse(InputStream input) {
        Map<CalcParamKey, BigDecimal> values = new EnumMap<>(CalcParamKey.class);
        for (Row row : CsvRows.read(input, HEADER)) {
            CalcParamKey key = readKey(row);
            BigDecimal value = row.readNumber(1, MAX_INTEGER_DIGITS, MAX_SCALE);
            if (!key.accepts(value)) {
                throw ReferenceDataLoadException.atLine(row.lineNumber(), "value is out of range for " + key);
            }
            if (values.put(key, value) != null) {
                throw ReferenceDataLoadException.atLine(row.lineNumber(), "duplicate key " + key);
            }
        }
        return values;
    }

    private static CalcParamKey readKey(Row row) {
        try {
            return CalcParamKey.valueOf(row.cells().get(0));
        } catch (IllegalArgumentException exception) {
            throw ReferenceDataLoadException.atLine(row.lineNumber(), "unknown param_key");
        }
    }
}
