package com.moeun.backend.calc.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.application.CalcEngine;

/**
 * 로컬 기준 데이터로 세 종목·두 계산 방식 비교 리포트(#162)를 만든다. 실행: {@code ./gradlew calcReport}.
 * <p>가격 파일이 개발자 로컬에만 있어(TBD-08) 평소 {@code ./gradlew test}에서는 돌지 않는다({@code report} 태그 제외).
 * 결과 {@code build/reports/calc/product-comparison.md}를 {@code docs/calc-report.md}에 옮겨 남긴다.
 */
@Tag("report")
class ProductComparisonReportGenerator {

    private static final Path OUTPUT = Path.of("build", "reports", "calc", "product-comparison.md");

    @Test
    @DisplayName("로컬 기준 데이터로 비교 리포트를 만들어 build/reports/calc/product-comparison.md에 쓴다")
    void writesReport() throws IOException {
        LocalSeedData seeds = LocalSeedData.read();
        ProductComparisonReport report = new ProductComparisonReport(new CalcEngine(), seeds.calcParams(),
                seeds.describeSavingsRate());

        String markdown = report.render(seeds.etfProducts(), ReportCondition.createDefaults(seeds.savingsRatePct()));

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, markdown, StandardCharsets.UTF_8);
    }
}
