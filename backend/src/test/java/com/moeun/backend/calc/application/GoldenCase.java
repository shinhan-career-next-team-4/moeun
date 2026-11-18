package com.moeun.backend.calc.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * test/resources/golden의 손계산 골든 케이스(plan 종류)를 읽는다.
 * 금액은 원 단위 정수, 금리·세율은 문자열 소수라서 BigDecimal로 읽는다(golden/README.md).
 */
record GoldenCase(String id, CalcInput input, CalcParams params, Expected expected) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long TEST_PARAM_VERSION_ID = 1L;

    static GoldenCase read(String fileName) {
        try (InputStream in = new ClassPathResource("golden/" + fileName).getInputStream()) {
            return from(JSON.readTree(in));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read golden case " + fileName, exception);
        }
    }

    private static GoldenCase from(JsonNode root) {
        JsonNode in = root.get("input");
        CalcInput input = new CalcInput(
                in.get("goalAmount").asLong(),
                in.get("targetPeriodMonths").asInt(),
                YearMonth.parse(in.get("calcStartMonth").asString()),
                in.get("heldAssets").asLong(),
                in.get("monthlySavingAmount").asLong(),
                new BigDecimal(in.get("savingsRatePct").asString()),
                in.get("savingsRatioPct").asInt(),
                Scenario.valueOf(in.get("baselineScenario").asString()));
        BigDecimal taxRatePct = new BigDecimal(root.get("params").get("taxRatePct").asString());
        DataVersionInfo version = new DataVersionInfo(TEST_PARAM_VERSION_ID, DataKind.CALC_PARAM, "golden",
                LocalDate.of(2026, 11, 17), "골든 케이스");
        CalcParams params = new CalcParams(version, Map.of(CalcParamKey.TAX_RATE_PCT, taxRatePct));
        return new GoldenCase(root.get("id").asString(), input, params, readExpected(root.get("expected")));
    }

    private static Expected readExpected(JsonNode node) {
        List<ExpectedScenario> scenarios = new ArrayList<>();
        for (JsonNode scenario : node.get("scenarios")) {
            List<MonthlyPoint> points = new ArrayList<>();
            for (JsonNode point : scenario.get("monthlyPoints")) {
                points.add(new MonthlyPoint(YearMonth.parse(point.get("month").asString()),
                        point.get("principal").asLong(), point.get("afterTaxAsset").asLong()));
            }
            JsonNode reachMonth = scenario.get("goalReachMonth");
            scenarios.add(new ExpectedScenario(
                    Scenario.valueOf(scenario.get("scenario").asString()),
                    scenario.get("afterTaxAsset").asLong(),
                    scenario.get("principal").asLong(),
                    scenario.get("afterTaxGain").asLong(),
                    scenario.get("gapToGoal").asLong(),
                    scenario.get("isAchieved").asBoolean(),
                    reachMonth.isNull() ? null : YearMonth.parse(reachMonth.asString()),
                    points));
        }
        return new Expected(CalcStatus.valueOf(node.get("status").asString()),
                YearMonth.parse(node.get("targetMonth").asString()), scenarios);
    }

    record Expected(CalcStatus status, YearMonth targetMonth, List<ExpectedScenario> scenarios) {
    }

    record ExpectedScenario(Scenario scenario, long afterTaxAsset, long principal, long afterTaxGain,
            long gapToGoal, boolean isAchieved, YearMonth goalReachMonth, List<MonthlyPoint> monthlyPoints) {
    }
}
