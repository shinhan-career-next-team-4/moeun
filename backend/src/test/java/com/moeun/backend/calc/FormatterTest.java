package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.moeun.backend.calc.application.Formatter;
import com.moeun.backend.config.copy.CopyBook;

/**
 * 표시 형식(CP-04, D-055, D-060, D-061, D-065). 템플릿은 실제 문구표(copy/copy.ko.yml)에서 읽는다.
 */
class FormatterTest {

    static final CopyBook COPY = CopyBook.load(Path.of("../copy/copy.ko.yml"));

    private final Formatter formatter = new Formatter(COPY);

    @Test
    @DisplayName("28,704,000원은 천 단위에서 반올림해 약 2,870만원이다")
    void formatsAmountInTenThousandWon() {
        assertThat(formatter.formatAmount(28_704_000L)).isEqualTo("약 2,870만원");
    }

    @Test
    @DisplayName("1억 원 이상도 단위를 바꾸지 않고 약 12,345만원이다(D-065)")
    void keepsTenThousandUnitAboveHundredMillion() {
        assertThat(formatter.formatAmount(123_450_000L)).isEqualTo("약 12,345만원");
    }

    @ParameterizedTest(name = "{0}원 → {1}")
    @CsvSource(delimiter = '|', value = {
        "0|0원",
        "3000|1만원 미만",
        "-1200000|-약 120만원",
        "-3000|-1만원 미만",
        "5000|약 1만원",
        "4999|1만원 미만"
    })
    @DisplayName("일반 금액 경계: 정확히 0원, 0만원으로 반올림, 음수(D-061)")
    void formatsAmountBoundaries(long won, String expected) {
        assertThat(formatter.formatAmount(won)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}원, 달성 {1} → {2}")
    @CsvSource(delimiter = '|', value = {
        "-2880000|false|-약 288만원 미달",
        "5010000|true|+약 501만원 달성",
        "0|true|0원 달성",
        "-3000|false|-1만원 미만 미달",
        "3000|true|+1만원 미만 달성"
    })
    @DisplayName("목표 대비는 부호 + 약 N만원 + 달성/미달이다(CP-04, D-055, D-065)")
    void formatsGapToGoal(long gap, boolean isAchieved, String expected) {
        assertThat(formatter.formatGapToGoal(gap, isAchieved)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1500000|true|달성 · 목표 +150만 원",
        "-200000|false|미달 · 목표 −20만 원",
        "0|true|달성 · 목표 0원",
        "-3000|false|미달 · 목표 −1만 원 미만",
        "3000|true|달성 · 목표 +1만 원 미만"
    })
    @DisplayName("결과 화면 배지는 판정 라벨 + 목표 +/−N만 원이다(약 없음, 라벨은 엔진 판정을 따른다, #224)")
    void formatsGapBadge(long gap, boolean isAchieved, String expected) {
        assertThat(formatter.formatGapBadge(gap, isAchieved)).isEqualTo(expected);
    }

    @Test
    @DisplayName("3천 원 부족하면 판정은 미달이고 표시도 미달이다(반올림하면 0만원이어도 '달성'처럼 보이지 않음)")
    void keepsLabelConsistentWithJudgementAtBoundary() {
        assertThat(formatter.formatGapToGoal(-3_000L, false)).startsWith("-").endsWith("미달");
    }

    @ParameterizedTest(name = "{0}원 → {1}")
    @CsvSource(delimiter = '|', value = {
        "7890000|약 789만원 증가",
        "-7890000|약 789만원 감소",
        "0|변화 없음",
        "3000|1만원 미만 증가",
        "-3000|1만원 미만 감소"
    })
    @DisplayName("차이는 부호가 아니라 방향 단어로 쓴다(D-055)")
    void formatsDifferenceWithDirectionWord(long difference, String expected) {
        assertThat(formatter.formatDifference(difference)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}개월 → {1}")
    @CsvSource(delimiter = '|', value = {"38|3년 2개월", "36|3년 0개월", "1|0년 1개월", "360|30년 0개월"})
    @DisplayName("기간은 n년 n개월이다")
    void formatsPeriod(int months, String expected) {
        assertThat(formatter.formatPeriod(months)).isEqualTo(expected);
    }

    @Test
    @DisplayName("시점은 YYYY년 M월이고, 날짜는 일을 버린다")
    void formatsMonth() {
        assertThat(formatter.formatMonth(YearMonth.of(2029, 11))).isEqualTo("2029년 11월");
        assertThat(formatter.formatMonth(YearMonth.of(2026, 3))).isEqualTo("2026년 3월");
        assertThat(formatter.formatMonth(LocalDate.of(2026, 10, 31))).isEqualTo("2026년 10월");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {"3.12|연 3.12%", "3.1|연 3.10%", "20|연 20.00%", "2.84|연 2.84%"})
    @DisplayName("금리는 연 N.NN%이고 끝의 0을 지우지 않는다")
    void formatsSavingsRate(String rate, String expected) {
        assertThat(formatter.formatSavingsRate(new BigDecimal(rate))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {"15.4|15.4%", "15.40|15.4%", "15.400|15.4%"})
    @DisplayName("세율은 끝 0을 뺀 N.N%다(스케일이 달라도 같음, D-060)")
    void formatsTaxRate(String rate, String expected) {
        assertThat(formatter.formatTaxRate(new BigDecimal(rate))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "적금 {0} → {1}")
    @CsvSource(delimiter = '|', value = {"50|적금 50 : ETF 50", "0|적금 0 : ETF 100", "100|적금 100 : ETF 0"})
    @DisplayName("비중은 적금 a : ETF b 하나다")
    void formatsRatio(int savingsRatioPct, String expected) {
        assertThat(formatter.formatRatio(savingsRatioPct)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}원 → {1}")
    @CsvSource(delimiter = '|', value = {"10000|1만원", "10000000|1,000만원", "0|0원"})
    @DisplayName("범위 경계값은 정확한 값이라 약 없이 쓴다(입력 범위 오류 표시, C3-1)")
    void formatsExactBoundaryAmount(long won, String expected) {
        assertThat(formatter.formatExactAmount(won)).isEqualTo(expected);
    }

    @Test
    @DisplayName("달성·미달·증가·감소·변화 없음·비중 템플릿은 문구표에서 읽는다")
    void readsTemplatesFromCopyBook() {
        assertThat(formatter.formatGapToGoal(0L, true)).endsWith(COPY.get("template.achieved"));
        assertThat(formatter.formatGapToGoal(-2_880_000L, false)).endsWith(COPY.get("template.missed"));
        assertThat(formatter.formatDifference(7_890_000L)).endsWith(COPY.get("template.increase"));
        assertThat(formatter.formatDifference(-7_890_000L)).endsWith(COPY.get("template.decrease"));
        assertThat(formatter.formatDifference(0L)).isEqualTo(COPY.get("template.unchanged"));
        assertThat(formatter.formatRatio(30)).isEqualTo(
                COPY.get("template.ratio", Map.of("a", "30", "b", "70")));
    }

    @Test
    @DisplayName("ETF 종목 세율: 비과세(0%)면 이유를 붙이고, 과세면 세율만 쓴다(#150)")
    void formatsEtfGainTaxRate() {
        assertThat(formatter.formatEtfGainTaxRate(new BigDecimal("0.000")))
                .isEqualTo("0%(" + COPY.get("tax.etfTaxFree") + ")")
                .isEqualTo("0%(국내 주식형 ETF 매매차익 비과세)");
        assertThat(formatter.formatEtfGainTaxRate(new BigDecimal("15.400"))).isEqualTo("15.4%");
    }
}
