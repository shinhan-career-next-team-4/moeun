package com.moeun.backend.calc.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 세 종목·두 계산 방식 비교 리포트(#162)의 표 만들기. 테스트용 가짜 종가와 적은 경로 수로 확인한다.
 * 실제 데이터로 만드는 일은 {@code ./gradlew calcReport}({@link ProductComparisonReportGenerator})가 한다.
 */
class ProductComparisonReportTest {

    private static final CalcParams PARAMS = ReportFixtures.createParams(500);
    private static final BigDecimal RATE = new BigDecimal("2.84");

    /** 매달 1.5%씩 오르는 종목(과세 15.4%). */
    private static final EtfSeries STEADY = ReportFixtures.createSeries("AAA", "가 지수 추종 ETF", new BigDecimal("15.4"),
            index -> BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(1.015).pow(index)));

    /** 크게 오르내리는 종목(비과세). */
    private static final EtfSeries VOLATILE = ReportFixtures.createSeries("BBB", "나 지수 추종 ETF", BigDecimal.ZERO,
            index -> BigDecimal.valueOf(index % 2 == 0 ? 100 + index : 80 + index));

    private static final EtfProduct MISSING = new EtfProduct("CCC", "다 지수 추종 ETF", new BigDecimal("15.4"));

    private final ProductComparisonReport report = new ProductComparisonReport(new CalcEngine(), PARAMS,
            "2.84%(테스트)");

    private static List<ReportProduct> products() {
        return List.of(ReportProduct.of(STEADY), ReportProduct.of(VOLATILE), ReportProduct.missing(MISSING));
    }

    private static List<ReportCondition> conditions() {
        return ReportCondition.createDefaults(RATE);
    }

    @Test
    @DisplayName("같은 데이터·같은 조건으로 두 번 만들면 글자까지 같다(시드 고정, 만든 시각을 넣지 않음)")
    void rendersSameTextTwice() {
        assertThat(report.render(products(), conditions())).isEqualTo(report.render(products(), conditions()));
    }

    @Test
    @DisplayName("표의 금액은 엔진 결과 그대로다: BAD·AVG·GOOD, 폭은 GOOD − BAD, 방식 A는 같은 수익률 시계열의 엔진 값")
    void copiesEngineResults() {
        // 크게 오르내리는 종목이라 BAD < AVG < GOOD이고 방식 A도 AVG와 다르다. 열이 뒤바뀌면 행이 달라진다
        ReportCondition condition = conditions().get(0);
        CalcResult bootstrap = new CalcEngine().calculate(condition.input(), PARAMS, VOLATILE);
        CalcResult cagr = new CalcEngine().calculate(condition.input(), PARAMS, CagrSeries.of(VOLATILE, 120));
        long bad = readAfterTax(bootstrap, Scenario.BAD);
        long avg = readAfterTax(bootstrap, Scenario.AVG);
        long good = readAfterTax(bootstrap, Scenario.GOOD);
        long methodA = readAfterTax(cagr, Scenario.AVG);
        ScenarioResult avgResult = findScenario(bootstrap, Scenario.AVG);
        assertThat(bad).isLessThan(avg);
        assertThat(avg).isLessThan(good);
        assertThat(methodA).isNotEqualTo(avg);

        String expectedRow = "| 나 지수 추종 ETF | " + ReportFormat.formatWon(bad) + " | " + ReportFormat.formatWon(avg)
                + " | " + ReportFormat.formatWon(good) + " | " + ReportFormat.formatWon(good - bad) + " | "
                + ReportFormat.formatWon(methodA) + " | "
                + ReportFormat.describeAchievement(avgResult.isAchieved(), avgResult.gapToGoal()) + " |";
        assertThat(report.render(products(), conditions()).lines()).contains(expectedRow);
    }

    @Test
    @DisplayName("(예외) 가격 파일이 없는 종목은 실패하지 않고 데이터 표와 조건 표에 '데이터 없음'으로 적는다")
    void marksMissingProduct() {
        List<String> lines = report.render(products(), conditions()).lines().toList();

        assertThat(lines.stream().filter(line -> line.startsWith("| 다 지수 추종 ETF |")))
                .hasSize(1 + conditions().size())
                .allMatch(line -> line.contains("데이터 없음"));
    }

    @Test
    @DisplayName("종목마다 관측 기간·ETF 차익 세율·방식 A 월 수익률을 적고, 비과세는 0%로 보인다")
    void describesData() {
        String markdown = report.render(products(), conditions());

        assertThat(markdown).contains("| 가 지수 추종 ETF | 2016-10 ~ 2026-10 | 2026-10-31 | 15.4% |");
        assertThat(markdown).contains("| 나 지수 추종 ETF | 2016-10 ~ 2026-10 | 2026-10-31 | 0% |");
        // 매달 1.5%씩 오르면 방식 A 월 수익률도 1.5%다(연 환산 19.56%)
        assertThat(markdown).contains("| 1.50% (연 19.56%) |");
    }

    @Test
    @DisplayName("고지(과거 데이터 예시, 특정 상품을 권하지 않음)가 있고 '추천' 표현이 없다(CP-06)")
    void keepsNoticeWithoutRecommendation() {
        String markdown = report.render(products(), conditions());

        assertThat(markdown).contains("과거 데이터로 계산한 예시").contains("특정 상품을 권하지 않");
        assertThat(markdown).doesNotContain("추천");
        assertThat(markdown).contains(CalcEngine.ENGINE_VERSION).contains("경로 500개").contains("시드 20261117");
    }

    @Test
    @DisplayName("기본 비교 조건은 기준 계획(계약 A-1), 같은 조건 ETF 100%, 장기 120개월 세 가지다")
    void definesDefaultConditions() {
        List<CalcInput> inputs = conditions().stream().map(ReportCondition::input).toList();

        assertThat(inputs).containsExactly(
                new CalcInput(20_000_000L, 36, YearMonth.of(2026, 11), 5_000_000L, 300_000L, RATE, 50, Scenario.AVG),
                new CalcInput(20_000_000L, 36, YearMonth.of(2026, 11), 5_000_000L, 300_000L, RATE, 0, Scenario.AVG),
                new CalcInput(100_000_000L, 120, YearMonth.of(2026, 11), 0L, 600_000L, RATE, 50, Scenario.AVG));
    }

    private static long readAfterTax(CalcResult result, Scenario scenario) {
        return findScenario(result, scenario).afterTaxAsset();
    }

    private static ScenarioResult findScenario(CalcResult result, Scenario scenario) {
        return result.scenarios().stream().filter(item -> item.scenario() == scenario).findFirst().orElseThrow();
    }
}
