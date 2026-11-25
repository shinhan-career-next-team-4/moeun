package com.moeun.backend.refdata.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 기준 데이터 CSV 공통 읽기. 값에 쉼표나 따옴표가 없는 단순한 형식만 다룬다.
 * 빈 줄과 '#'로 시작하는 줄은 건너뛰어, 파일 안에 출처나 테스트용 표시를 적을 수 있게 한다.
 */
final class CsvRows {

    private static final String COMMENT_PREFIX = "#";
    private static final String BYTE_ORDER_MARK = "﻿";

    private CsvRows() {
    }

    static List<Row> read(InputStream input, List<String> expectedHeader) {
        List<Row> rows = new ArrayList<>();
        boolean hasHeader = false;
        int lineNumber = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String text = line.replace(BYTE_ORDER_MARK, "").strip();
                if (text.isEmpty() || text.startsWith(COMMENT_PREFIX)) {
                    continue;
                }
                List<String> cells = Arrays.stream(text.split(",", -1)).map(String::strip).toList();
                if (!hasHeader) {
                    requireHeader(lineNumber, cells, expectedHeader);
                    hasHeader = true;
                    continue;
                }
                if (cells.size() != expectedHeader.size()) {
                    throw ReferenceDataLoadException.atLine(lineNumber,
                            "expected " + expectedHeader.size() + " columns but found " + cells.size());
                }
                rows.add(new Row(lineNumber, cells));
            }
        } catch (IOException exception) {
            throw new ReferenceDataLoadException("failed to read file", exception);
        }
        if (rows.isEmpty()) {
            throw new ReferenceDataLoadException("file has no data rows");
        }
        return rows;
    }

    private static void requireHeader(int lineNumber, List<String> cells, List<String> expectedHeader) {
        if (!cells.equals(expectedHeader)) {
            throw ReferenceDataLoadException.atLine(lineNumber, "header must be " + String.join(",", expectedHeader));
        }
    }

    /**
     * 헤더를 뺀 데이터 한 줄.
     *
     * @param lineNumber 파일에서의 줄 번호(1부터)
     * @param cells 칸별 값
     */
    record Row(int lineNumber, List<String> cells) {

        YearMonth readMonth(int index) {
            try {
                return YearMonth.parse(cells.get(index));
            } catch (DateTimeException exception) {
                throw ReferenceDataLoadException.atLine(lineNumber, "month must be yyyy-MM");
            }
        }

        /** 숫자를 읽고 DB 컬럼(NUMERIC)에 들어가는 자릿수인지 확인한다. */
        BigDecimal readNumber(int index, int maxIntegerDigits, int maxScale) {
            BigDecimal number;
            try {
                number = new BigDecimal(cells.get(index));
            } catch (NumberFormatException exception) {
                throw ReferenceDataLoadException.atLine(lineNumber, "not a number in column " + (index + 1));
            }
            BigDecimal stripped = number.stripTrailingZeros();
            if (stripped.scale() > maxScale) {
                throw ReferenceDataLoadException.atLine(lineNumber,
                        "at most " + maxScale + " decimal places allowed");
            }
            if (stripped.precision() - stripped.scale() > maxIntegerDigits) {
                throw ReferenceDataLoadException.atLine(lineNumber, "number is too large");
            }
            return number;
        }
    }
}
