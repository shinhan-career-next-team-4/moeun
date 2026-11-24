package com.moeun.backend.calc.report;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.application.BootstrapPaths;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 기준 계획 한 개의 부트스트랩 경로별 최종 금액 리포트(#302, 발표 분포 그래프용).
 * <p>금액은 엔진과 같은 계산으로 다시 만든 경로 값({@link BootstrapPaths})을 그대로 쓴다. 이 클래스는 줄 세우기·개수 세기·
 * 비율만 한다. 만든 시각을 넣지 않아 같은 입력이면 글자까지 같은 파일이 나온다.
 *
 * @param title 조건 이름
 * @param input 엔진 입력
 * @param series 계산에 쓴 ETF 종목 시계열
 * @param paths 경로별 최종 금액과 3구간
 * @param binWidth 히스토그램 구간 폭(원)
 */
record BootstrapPathsReport(String title, CalcInput input, EtfSeries series, BootstrapPaths.Result paths,
        long binWidth) {

    /** 발표 그래프의 기본 구간 폭 50만원. */
    static final long DEFAULT_BIN_WIDTH = 500_000L;

    private static final String NEW_LINE = "\n";

    BootstrapPathsReport {
        if (binWidth <= 0) {
            throw new IllegalArgumentException("binWidth must be positive");
        }
    }

    /** 원금 = 보유 자산 + 월 저축액 × 기간. */
    long principal() {
        return input.heldAssets() + input.monthlySavingAmount() * input.targetPeriodMonths();
    }

    /** 경로 한 줄씩: 경로 번호(1부터), 최종 세후 평가액(원), 원금 대비 손익(원). */
    String renderPathsCsv() {
        StringBuilder out = new StringBuilder("path,final_after_tax_asset,gain_vs_principal").append(NEW_LINE);
        List<Long> finals = paths.finalWon();
        for (int index = 0; index < finals.size(); index++) {
            long value = finals.get(index);
            out.append(index + 1).append(',').append(value).append(',').append(value - principal()).append(NEW_LINE);
        }
        return out.toString();
    }

    /** 히스토그램 구간 한 줄씩: 구간 시작(포함), 구간 끝(제외), 경로 수. 빈 구간도 최솟값~최댓값 사이면 넣는다. */
    String renderHistogramCsv() {
        StringBuilder out = new StringBuilder("bin_start,bin_end,count").append(NEW_LINE);
        for (Bin bin : histogram()) {
            out.append(bin.start()).append(',').append(bin.end()).append(',').append(bin.count()).append(NEW_LINE);
        }
        return out.toString();
    }

    List<Bin> histogram() {
        List<Long> finals = paths.finalWon();
        long first = floorToBin(finals.stream().mapToLong(Long::longValue).min().orElseThrow());
        long last = floorToBin(finals.stream().mapToLong(Long::longValue).max().orElseThrow());
        int binCount = (int)((last - first) / binWidth) + 1;
        long[] counts = new long[binCount];
        for (long value : finals) {
            counts[(int)((floorToBin(value) - first) / binWidth)]++;
        }
        List<Bin> bins = new ArrayList<>(binCount);
        for (int index = 0; index < binCount; index++) {
            long start = first + binWidth * index;
            bins.add(new Bin(start, start + binWidth, counts[index]));
        }
        return bins;
    }

    private long floorToBin(long value) {
        return Math.floorDiv(value, binWidth) * binWidth;
    }

    long countAtLeast(long threshold) {
        return paths.finalWon().stream().filter(value -> value >= threshold).count();
    }

    /** 발표 자료에 옮길 요약(Markdown). 원자료 파일 이름은 기준 계획 것({@code bootstrap-paths})으로 적는다. */
    String renderSummary() {
        return renderSummary("bootstrap-paths");
    }

    /**
     * 발표 자료에 옮길 요약(Markdown).
     *
     * @param filePrefix 함께 만든 원자료 CSV 파일 이름 앞부분
     */
    String renderSummary(String filePrefix) {
        List<Long> finals = paths.finalWon();
        long count = finals.size();
        long reached = countAtLeast(input.goalAmount());
        long belowPrincipal = count - countAtLeast(principal());
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append(" ").append(ReportFormat.formatCount(count)).append("가지 경우 분포\n\n")
                .append("> 과거 데이터로 계산한 예시입니다. 특정 상품을 권하지 않으며, 앞으로의 수익을 보장하지 않습니다.\n\n")
                .append("## 조건\n\n")
                .append("- ").append(title).append(": 목표 ").append(ReportFormat.formatWon(input.goalAmount()))
                .append(", ").append(input.targetPeriodMonths()).append("개월, 보유 ")
                .append(ReportFormat.formatWon(input.heldAssets())).append(", 월 ")
                .append(ReportFormat.formatWon(input.monthlySavingAmount())).append(", 적금 ")
                .append(input.savingsRatioPct()).append(" : ETF ").append(100 - input.savingsRatioPct()).append('\n')
                .append("- 적금 금리 ").append(ReportFormat.formatPercentValue(input.savingsRatePct()))
                .append(", ETF ").append(series.product().displayName()).append("(매매차익 세율 ")
                .append(ReportFormat.formatPercentValue(series.product().gainTaxRatePct())).append("), 데이터 기준일 ")
                .append(series.version().baseDate()).append('\n')
                .append("- 원금(보유 자산 + 월 저축액 × 기간) ").append(ReportFormat.formatWon(principal())).append("\n\n")
                .append("## 세 숫자(결과 화면과 같은 값)\n\n")
                .append("| 구간 | 최종 금액 |\n|---|---:|\n")
                .append("| 나빴을 때(하위 10%) | ").append(ReportFormat.formatWon(paths.badWon())).append(" |\n")
                .append("| 평균 | ").append(ReportFormat.formatWon(paths.averageWon())).append(" |\n")
                .append("| 좋았을 때(상위 10%) | ").append(ReportFormat.formatWon(paths.goodWon())).append(" |\n\n")
                .append("## 1만 가지 경우\n\n")
                .append("| 항목 | 값 |\n|---|---:|\n")
                .append("| 가장 나빴던 경우 | ")
                .append(ReportFormat.formatWon(finals.stream().mapToLong(Long::longValue).min().orElseThrow()))
                .append(" |\n")
                .append("| 가장 좋았던 경우 | ")
                .append(ReportFormat.formatWon(finals.stream().mapToLong(Long::longValue).max().orElseThrow()))
                .append(" |\n")
                .append("| 목표(").append(ReportFormat.formatWon(input.goalAmount())).append(") 이상 | ")
                .append(describeShare(reached, count)).append(" |\n")
                .append("| 원금보다 적게 끝난 경우 | ").append(describeShare(belowPrincipal, count)).append(" |\n\n")
                .append("## 구간별 경우 수(").append(ReportFormat.formatCount(binWidth / 10_000)).append("만원 단위)\n\n")
                .append("| 구간 | 경우 수 | 비율 |\n|---|---:|---:|\n");
        for (Bin bin : histogram()) {
            out.append("| ").append(formatManwon(bin.start())).append(" 이상 ").append(formatManwon(bin.end()))
                    .append(" 미만 | ").append(ReportFormat.formatCount(bin.count())).append(" | ")
                    .append(ReportFormat.formatRatioAsPercent(ratio(bin.count(), count))).append(" |\n");
        }
        out.append("\n그래프용 원자료: `").append(filePrefix).append(".csv`(경로별), `").append(filePrefix)
                .append("-histogram.csv`(구간별)\n");
        return out.toString();
    }

    private static String describeShare(long part, long whole) {
        return ReportFormat.formatCount(part) + "가지 (" + ReportFormat.formatRatioAsPercent(ratio(part, whole)) + ")";
    }

    private static BigDecimal ratio(long part, long whole) {
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), MathContext.DECIMAL128);
    }

    /** 15,500,000 → 1,550만원. 구간 경계는 50만원 단위라 나누어떨어진다. */
    private static String formatManwon(long won) {
        return ReportFormat.formatCount(won / 10_000) + "만원";
    }

    /**
     * 히스토그램 구간 하나.
     *
     * @param start 시작(포함, 원)
     * @param end 끝(제외, 원)
     * @param count 이 구간에서 끝난 경로 수
     */
    record Bin(long start, long end, long count) {
    }
}
