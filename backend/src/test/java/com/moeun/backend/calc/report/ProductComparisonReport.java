package com.moeun.backend.calc.report;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.EtfSeries.MonthlyClose;

/**
 * 세 종목·두 계산 방식 비교 리포트(#162)를 Markdown으로 만든다.
 * <p>금액은 모두 {@link CalcEngine}이 계산한다(NFR-INT-01). 이 클래스는 엔진 결과를 표로 옮기고 결과끼리 뺄셈(폭)만 한다.
 * 방식 A(CAGR)도 엔진에 {@link CagrSeries}를 넣어 구한다. 만든 시각을 넣지 않아 같은 입력이면 글자까지 같은 문서가 나온다.
 */
final class ProductComparisonReport {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final int MONTHS_PER_YEAR = 12;
    private static final String NONE = "−";
    private static final String MISSING = "데이터 없음";

    private final CalcEngine engine;
    private final CalcParams params;
    private final String savingsRateNote;

    /**
     * @param engine 계산 엔진
     * @param params 계산 파라미터(세율, 관측 기간, 분위, 경로 수, 시드)
     * @param savingsRateNote 적금 금리 설명(예: "2.84%(은행연합회 평균, 2026-10)")
     */
    ProductComparisonReport(CalcEngine engine, CalcParams params, String savingsRateNote) {
        this.engine = engine;
        this.params = params;
        this.savingsRateNote = savingsRateNote;
    }

    String render(List<ReportProduct> products, List<ReportCondition> conditions) {
        StringBuilder out = new StringBuilder();
        appendHeader(out);
        appendDataTable(out, products);
        for (int index = 0; index < conditions.size(); index++) {
            appendCondition(out, index + 1, conditions.get(index), products);
        }
        appendGuide(out);
        return out.toString();
    }

    private void appendHeader(StringBuilder out) {
        out.append("# 세 종목·두 계산 방식 비교 리포트\n\n")
                .append("> 과거 데이터로 계산한 예시입니다. 특정 상품을 권하지 않으며, 앞으로의 수익을 보장하지 않습니다.\n")
                .append("> 금액은 모두 계산 엔진(`CalcEngine`, ").append(CalcEngine.ENGINE_VERSION)
                .append(")이 계산했고, 이 문서는 `./gradlew calcReport`로 만듭니다(#162).\n\n")
                .append("## 계산 기준\n\n")
                .append("- 3구간(지금 방식): 부트스트랩. 최근 ").append(readObservationMonths())
                .append("개월 월 수익률에서 복원추출, 경로 ").append(ReportFormat.formatCount(readPathCount()))
                .append("개, 시드 ").append(params.getValue(CalcParamKey.BOOTSTRAP_SEED).toPlainString())
                .append(". BAD = 하위 ").append(formatPercentile(CalcParamKey.SCENARIO_LOW_PERCENTILE))
                .append(", AVG = 평균, GOOD = 상위 ").append(formatPercentile(CalcParamKey.SCENARIO_HIGH_PERCENTILE))
                .append("\n")
                .append("- 방식 A(CAGR, 비교용): 관측 기간의 첫 종가와 마지막 종가로 구한 월 수익률 g를 매달 똑같이 적용한다. ")
                .append("구간이 갈리지 않는다(calc-spec 7-4)\n")
                .append("- 적금: 금리 ").append(savingsRateNote).append(", 단리, 이자 세율 ")
                .append(ReportFormat.formatPercentValue(params.getValue(CalcParamKey.TAX_RATE_PCT))).append("\n")
                .append("- ETF: 매월 초 적립. 그 달에 모두 판다고 볼 때의 매매차익 세금을 뺀 값(손실이면 0). 세율은 종목별\n")
                .append("- 반영하지 않은 것: ETF 총보수, 환율, 분배금, 보유 자산의 운용 수익, 적금 만기 뒤 재예치\n\n");
    }

    private void appendDataTable(StringBuilder out, List<ReportProduct> products) {
        out.append("## 데이터\n\n")
                .append("| 종목 | 관측 기간 | 기준일 | ETF 차익 세율 | 월 수익률 최저 | 월 수익률 최고 | 방식 A 월 수익률 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (ReportProduct product : products) {
            String name = product.product().displayName();
            Optional<EtfSeries> series = product.findSeries();
            if (series.isEmpty()) {
                out.append(formatRow(name, MISSING, NONE, NONE, NONE, NONE, NONE));
                continue;
            }
            List<MonthlyClose> window = selectWindow(series.get());
            MonthlyReturn lowest = null;
            MonthlyReturn highest = null;
            for (int index = 1; index < window.size(); index++) {
                MonthlyReturn current = new MonthlyReturn(window.get(index), window.get(index - 1));
                if (lowest == null || current.value().compareTo(lowest.value()) < 0) {
                    lowest = current;
                }
                if (highest == null || current.value().compareTo(highest.value()) > 0) {
                    highest = current;
                }
            }
            BigDecimal rate = CagrSeries.calculateMonthlyRate(series.get(), readObservationMonths());
            BigDecimal annual = BigDecimal.ONE.add(rate).pow(MONTHS_PER_YEAR, PRECISION).subtract(BigDecimal.ONE);
            out.append(formatRow(name,
                    window.get(0).month() + " ~ " + window.get(window.size() - 1).month(),
                    series.get().version().baseDate().toString(),
                    ReportFormat.formatPercentValue(product.product().gainTaxRatePct()),
                    lowest.describe(), highest.describe(),
                    ReportFormat.formatRatioAsPercent(rate) + " (연 " + ReportFormat.formatRatioAsPercent(annual)
                            + ")"));
        }
        out.append("\n");
    }

    private void appendCondition(StringBuilder out, int number, ReportCondition condition,
            List<ReportProduct> products) {
        CalcInput input = condition.input();
        List<String> rows = new ArrayList<>();
        Long principal = null;
        for (ReportProduct product : products) {
            String name = product.product().displayName();
            Optional<EtfSeries> series = product.findSeries();
            if (series.isEmpty()) {
                rows.add(formatRow(name, MISSING, NONE, NONE, NONE, NONE, NONE));
                continue;
            }
            CalcResult bootstrap = engine.calculate(input, params, series.get());
            CalcResult cagr = engine.calculate(input, params, CagrSeries.of(series.get(), readObservationMonths()));
            ScenarioResult bad = findScenario(bootstrap, Scenario.BAD);
            ScenarioResult avg = findScenario(bootstrap, Scenario.AVG);
            ScenarioResult good = findScenario(bootstrap, Scenario.GOOD);
            principal = avg.principal();
            rows.add(formatRow(name,
                    ReportFormat.formatWon(bad.afterTaxAsset()),
                    ReportFormat.formatWon(avg.afterTaxAsset()),
                    ReportFormat.formatWon(good.afterTaxAsset()),
                    ReportFormat.formatWon(good.afterTaxAsset() - bad.afterTaxAsset()),
                    ReportFormat.formatWon(findScenario(cagr, Scenario.AVG).afterTaxAsset()),
                    ReportFormat.describeAchievement(avg.isAchieved(), avg.gapToGoal())));
        }
        out.append("## 조건 ").append(number).append(". ").append(condition.title()).append("\n\n")
                .append("목표 ").append(ReportFormat.formatWon(input.goalAmount()))
                .append(" · ").append(input.targetPeriodMonths()).append("개월(").append(input.calcStartMonth())
                .append("부터)")
                .append(" · 보유 ").append(ReportFormat.formatWon(input.heldAssets()))
                .append(" · 월 ").append(ReportFormat.formatWon(input.monthlySavingAmount()))
                .append(" · 적금 ").append(input.savingsRatioPct()).append(" : ETF ")
                .append(100 - input.savingsRatioPct())
                .append(" · 원금 ").append(principal == null ? NONE : ReportFormat.formatWon(principal)).append("\n\n")
                .append("| 종목 | BAD(하위 ").append(formatPercentile(CalcParamKey.SCENARIO_LOW_PERCENTILE))
                .append(") | AVG(평균) | GOOD(상위 ").append(formatPercentile(CalcParamKey.SCENARIO_HIGH_PERCENTILE))
                .append(") | BAD~GOOD 폭 | 방식 A(CAGR) | AVG 목표 대비 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        rows.forEach(out::append);
        out.append("\n");
    }

    private static void appendGuide(StringBuilder out) {
        out.append("## 읽는 법\n\n")
                .append("- BAD~GOOD 폭 = GOOD − BAD. 과거 월 수익률이 크게 오르내린 종목일수록 넓다.\n")
                .append("- AVG는 경로 값들의 산술평균이고, 방식 A는 관측 기간의 평균 성장률(기하평균)을 그대로 이은 한 줄이다. ")
                .append("오르내림이 클수록 산술평균이 기하평균보다 커지므로 AVG가 방식 A보다 크게 나오기 쉽다.\n")
                .append("- ETF 100% 조건은 적금 몫이 없어 종목 차이(수익률과 세율)가 가장 크게 보인다.\n")
                .append("- 세후 값은 그 달에 모두 판다고 볼 때의 값이다. 세율 0%인 종목은 매매차익에 세금이 없다.\n")
                .append("- 금액은 원 단위로 반올림한 엔진 결과다.\n");
    }

    private static String formatRow(String... cells) {
        StringJoiner joiner = new StringJoiner(" | ", "| ", " |\n");
        for (String cell : cells) {
            joiner.add(cell);
        }
        return joiner.toString();
    }

    private static ScenarioResult findScenario(CalcResult result, Scenario scenario) {
        return result.scenarios().stream()
                .filter(item -> item.scenario() == scenario)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("scenario " + scenario + " is missing"));
    }

    private List<MonthlyClose> selectWindow(EtfSeries series) {
        List<MonthlyClose> closes = series.monthlyCloses();
        int requiredCloses = readObservationMonths() + 1;
        if (closes.size() < requiredCloses) {
            throw new IllegalStateException("report needs at least " + requiredCloses + " monthly closes");
        }
        return closes.subList(closes.size() - requiredCloses, closes.size());
    }

    private int readObservationMonths() {
        return params.getValue(CalcParamKey.SCENARIO_OBSERVATION_MONTHS).intValueExact();
    }

    private int readPathCount() {
        return params.getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT).intValueExact();
    }

    private String formatPercentile(CalcParamKey key) {
        return ReportFormat.formatPercentValue(params.getValue(key));
    }

    /** 한 달 수익률 r = 종가(t) ÷ 종가(t−1) − 1과 그 달. 표에 최저·최고를 적을 때만 쓴다. */
    private record MonthlyReturn(MonthlyClose close, BigDecimal value) {

        MonthlyReturn(MonthlyClose current, MonthlyClose previous) {
            this(current, current.closeValue().divide(previous.closeValue(), PRECISION).subtract(BigDecimal.ONE));
        }

        String describe() {
            return ReportFormat.formatRatioAsPercent(value) + " (" + close.month() + ")";
        }
    }
}
