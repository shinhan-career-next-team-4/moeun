package com.moeun.backend.calc.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.application.BootstrapPaths;
import com.moeun.backend.refdata.EtfSeries;

/** 1만 경로 리포트(#302)의 CSV·구간 세기·요약. 경로 값은 손으로 넣은 작은 목록이다. */
class BootstrapPathsReportTest {

    private static final EtfSeries SERIES = ReportFixtures.createSeries("SP500", "S&P500 추종 ETF",
            new BigDecimal("15.4"), index -> BigDecimal.valueOf(1_000 + index));
    /** 목표 2,000만원, 36개월, 보유 500만원, 월 30만원 → 원금 1,580만원. */
    private static final CalcInput INPUT = new CalcInput(20_000_000L, 36, YearMonth.of(2026, 11), 5_000_000L,
            300_000L, new BigDecimal("2.84"), 50, Scenario.AVG);

    private static BootstrapPathsReport reportOf(long... finals) {
        List<BigDecimal> totals = java.util.Arrays.stream(finals).mapToObj(BigDecimal::valueOf).toList();
        return new BootstrapPathsReport("기준 계획", INPUT, SERIES,
                new BootstrapPaths.Result(totals, new BigDecimal("0.1"), new BigDecimal("0.9")),
                BootstrapPathsReport.DEFAULT_BIN_WIDTH);
    }

    @Test
    @DisplayName("경로 CSV는 경로 번호(1부터)·최종 금액·원금 대비 손익을 한 줄씩 쓴다")
    void writesOneLinePerPath() {
        assertThat(reportOf(15_000_000L, 20_000_000L).renderPathsCsv()).isEqualTo("""
                path,final_after_tax_asset,gain_vs_principal
                1,15000000,-800000
                2,20000000,4200000
                """);
    }

    @Test
    @DisplayName("구간은 50만원 단위로 시작 포함·끝 제외이고, 최솟값~최댓값 사이의 빈 구간도 0으로 넣는다")
    void countsPathsPerBin() {
        BootstrapPathsReport report = reportOf(15_499_999L, 15_500_000L, 15_999_999L, 17_000_000L);

        assertThat(report.histogram()).containsExactly(
                new BootstrapPathsReport.Bin(15_000_000L, 15_500_000L, 1),
                new BootstrapPathsReport.Bin(15_500_000L, 16_000_000L, 2),
                new BootstrapPathsReport.Bin(16_000_000L, 16_500_000L, 0),
                new BootstrapPathsReport.Bin(16_500_000L, 17_000_000L, 0),
                new BootstrapPathsReport.Bin(17_000_000L, 17_500_000L, 1));
        assertThat(report.renderHistogramCsv()).startsWith("bin_start,bin_end,count\n15000000,15500000,1\n");
    }

    @Test
    @DisplayName("목표와 같은 금액은 달성으로 센다(0원은 달성, CP-01)")
    void countsGoalAmountAsReached() {
        assertThat(reportOf(19_999_999L, 20_000_000L, 21_000_000L).countAtLeast(20_000_000L)).isEqualTo(2);
    }

    @Test
    @DisplayName("요약에 조건·세 숫자·최솟값·최댓값·목표 달성 비율·원금 미달 비율·구간표가 들어간다")
    void summarizesForSlides() {
        String summary = reportOf(15_000_000L, 16_000_000L, 17_000_000L, 20_000_000L).renderSummary();

        assertThat(summary)
                .contains("목표 20,000,000원, 36개월, 보유 5,000,000원, 월 300,000원, 적금 50 : ETF 50")
                .contains("원금(보유 자산 + 월 저축액 × 기간) 15,800,000원")
                .contains("| 가장 나빴던 경우 | 15,000,000원 |")
                .contains("| 가장 좋았던 경우 | 20,000,000원 |")
                .contains("| 목표(20,000,000원) 이상 | 1가지 (25.00%) |")
                .contains("| 원금보다 적게 끝난 경우 | 1가지 (25.00%) |")
                .contains("| 1,500만원 이상 1,550만원 미만 | 1 | 25.00% |")
                .contains("특정 상품을 권하지 않으며");
    }

    @Test
    @DisplayName("요약 제목은 조건 이름이고, 원자료 파일 이름은 넘긴 앞부분을 쓴다")
    void namesTitleAndRawFilesByCondition() {
        String summary = reportOf(15_000_000L).renderSummary("bootstrap-paths-demo");

        assertThat(summary).startsWith("# 기준 계획 1가지 경우 분포")
                .contains("`bootstrap-paths-demo.csv`(경로별), `bootstrap-paths-demo-histogram.csv`(구간별)");
    }

    @Test
    @DisplayName("발표 데모 시나리오는 전세자금 5,000만원, 36개월, 보유 600만원, 월 120만원, 적금 50 : ETF 50이다")
    void definesDemoScenario() {
        CalcInput demo = ReportCondition.createDemo(new BigDecimal("2.84")).input();

        assertThat(demo.goalAmount()).isEqualTo(50_000_000L);
        assertThat(demo.targetPeriodMonths()).isEqualTo(36);
        assertThat(demo.heldAssets()).isEqualTo(6_000_000L);
        assertThat(demo.monthlySavingAmount()).isEqualTo(1_200_000L);
        assertThat(demo.savingsRatioPct()).isEqualTo(50);
    }

    @Test
    @DisplayName("같은 경로 값이면 글자까지 같은 요약이 나온다(만든 시각 없음)")
    void isDeterministic() {
        assertThat(reportOf(15_000_000L, 18_000_000L).renderSummary())
                .isEqualTo(reportOf(15_000_000L, 18_000_000L).renderSummary());
    }
}
