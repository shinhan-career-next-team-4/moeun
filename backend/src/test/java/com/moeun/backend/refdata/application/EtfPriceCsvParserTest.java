package com.moeun.backend.refdata.application;

import static com.moeun.backend.refdata.application.CsvFixtures.createSp500Csv;
import static com.moeun.backend.refdata.application.CsvFixtures.toStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.refdata.EtfSeries.MonthlyClose;

class EtfPriceCsvParserTest {

    private static final YearMonth START_MONTH = YearMonth.of(2015, 1);
    private static final int ENOUGH_MONTHS = 130;

    @Test
    @DisplayName("월과 종가를 읽어 월 오름차순으로 돌려준다")
    void parsesMonthlyCloses() {
        List<MonthlyClose> closes = EtfPriceCsvParser.parse(toStream(createSp500Csv(START_MONTH, ENOUGH_MONTHS)));

        assertThat(closes).hasSize(ENOUGH_MONTHS);
        assertThat(closes.get(0)).isEqualTo(new MonthlyClose(START_MONTH, new BigDecimal("100")));
        assertThat(closes.get(ENOUGH_MONTHS - 1).month()).isEqualTo(START_MONTH.plusMonths(ENOUGH_MONTHS - 1));
    }

    @Test
    @DisplayName("줄 순서가 섞여 있어도 월 순서로 정렬한다")
    void sortsByMonth() {
        String csv = createSp500Csv(START_MONTH.plusMonths(1), ENOUGH_MONTHS) + START_MONTH + ",99.5\n";

        List<MonthlyClose> closes = EtfPriceCsvParser.parse(toStream(csv));

        assertThat(closes.get(0)).isEqualTo(new MonthlyClose(START_MONTH, new BigDecimal("99.5")));
    }

    @Test
    @DisplayName("주석 줄과 빈 줄은 건너뛴다")
    void skipsCommentAndBlankLines() {
        String csv = "# 테스트용\n\n" + createSp500Csv(START_MONTH, ENOUGH_MONTHS);

        assertThat(EtfPriceCsvParser.parse(toStream(csv))).hasSize(ENOUGH_MONTHS);
    }

    @Test
    @DisplayName("121개월은 받고 120개월은 거부한다")
    void requiresMinimumMonthCount() {
        assertThat(EtfPriceCsvParser.parse(toStream(createSp500Csv(START_MONTH, EtfPriceCsvParser.MIN_MONTH_COUNT))))
                .hasSize(EtfPriceCsvParser.MIN_MONTH_COUNT);
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(
                toStream(createSp500Csv(START_MONTH, EtfPriceCsvParser.MIN_MONTH_COUNT - 1))))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("at least 121 months");
    }

    @Test
    @DisplayName("빠진 달이 있으면 거부한다")
    void rejectsMissingMonth() {
        String csv = createSp500Csv(START_MONTH, ENOUGH_MONTHS).replace("2016-03,114\n", "");

        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream(csv)))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("missing month 2016-03");
    }

    @Test
    @DisplayName("같은 달이 두 번 나오면 줄 번호와 함께 거부한다")
    void rejectsDuplicateMonth() {
        String csv = createSp500Csv(START_MONTH, ENOUGH_MONTHS) + "2015-01,100\n";

        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream(csv)))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessage("line 132: duplicate month 2015-01");
    }

    @Test
    @DisplayName("종가가 0이거나 음수면 거부한다")
    void rejectsNonPositiveClose() {
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015-01,0\n")))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessage("line 2: close_value must be positive");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015-01,-1.5\n")))
                .isInstanceOf(ReferenceDataLoadException.class);
    }

    @Test
    @DisplayName("형식이 틀린 파일은 거부한다")
    void rejectsMalformedFile() {
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("month,close\n2015-01,100\n")))
                .hasMessageContaining("header must be ym,close_value");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015/01,100\n")))
                .hasMessage("line 2: month must be yyyy-MM");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015-01,abc\n")))
                .hasMessage("line 2: not a number in column 2");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015-01,100,7\n")))
                .hasMessage("line 2: expected 2 columns but found 3");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015-01,100.12345\n")))
                .hasMessage("line 2: at most 4 decimal places allowed");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n2015-01,12345678901\n")))
                .hasMessage("line 2: number is too large");
    }

    @Test
    @DisplayName("데이터 줄이 없으면 거부한다")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("")))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessage("file has no data rows");
        assertThatThrownBy(() -> EtfPriceCsvParser.parse(toStream("ym,close_value\n")))
                .hasMessage("file has no data rows");
    }
}
