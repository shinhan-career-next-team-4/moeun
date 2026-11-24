package com.moeun.backend.calc.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.BootstrapPaths;
import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 로컬 기준 데이터로 기준 계획의 1만 경로 리포트(#302)를 만든다. 실행: {@code ./gradlew calcReport}.
 * <p>가격 파일이 개발자 로컬에만 있어(TBD-08) 평소 {@code ./gradlew test}에서는 돌지 않고({@code report} 태그 제외),
 * S&P500 파일이 없으면 건너뛴다. 결과는 {@code build/reports/calc/} 아래에 남고 레포에 커밋하지 않는다.
 * <p>파일을 쓰기 전에 경로 값에서 고른 3구간이 엔진 결과와 원 단위까지 같은지 확인한다. 다르면 실패하고 쓰지 않는다.
 */
@Tag("report")
class BootstrapPathsReportGenerator {

    private static final Path DIRECTORY = Path.of("build", "reports", "calc");

    @Test
    @DisplayName("기준 계획의 경로별 최종 금액·구간별 개수·요약을 build/reports/calc에 쓴다")
    void writesReport() throws IOException {
        LocalSeedData seeds = LocalSeedData.read();
        Optional<EtfSeries> sp500 = seeds.etfProducts().stream()
                .filter(product -> EtfProduct.REPRESENTATIVE_CODE.equals(product.product().code()))
                .findFirst().flatMap(ReportProduct::findSeries);
        assumeTrue(sp500.isPresent(), "로컬 S&P500 가격 파일이 없어 건너뜀");
        ReportCondition condition = ReportCondition.createDefaults(seeds.savingsRatePct()).get(0);

        BootstrapPaths.Result paths = BootstrapPaths.simulate(condition.input(), seeds.calcParams(), sp500.get());
        CalcResult engine = new CalcEngine().calculate(condition.input(), seeds.calcParams(), sp500.get());

        assertThat(paths.finalTotals()).hasSize(
                seeds.calcParams().getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT).intValueExact());
        assertThat(paths.badWon()).isEqualTo(engineFinal(engine, Scenario.BAD));
        assertThat(paths.averageWon()).isEqualTo(engineFinal(engine, Scenario.AVG));
        assertThat(paths.goodWon()).isEqualTo(engineFinal(engine, Scenario.GOOD));

        BootstrapPathsReport report = new BootstrapPathsReport(condition.title(), condition.input(), sp500.get(),
                paths, BootstrapPathsReport.DEFAULT_BIN_WIDTH);
        Files.createDirectories(DIRECTORY);
        Files.writeString(DIRECTORY.resolve("bootstrap-paths.csv"), report.renderPathsCsv(), StandardCharsets.UTF_8);
        Files.writeString(DIRECTORY.resolve("bootstrap-paths-histogram.csv"), report.renderHistogramCsv(),
                StandardCharsets.UTF_8);
        Files.writeString(DIRECTORY.resolve("bootstrap-paths-summary.md"), report.renderSummary(),
                StandardCharsets.UTF_8);
    }

    private static long engineFinal(CalcResult result, Scenario scenario) {
        return result.scenarios().stream().filter(s -> s.scenario() == scenario).findFirst()
                .map(ScenarioResult::afterTaxAsset).orElseThrow();
    }
}
