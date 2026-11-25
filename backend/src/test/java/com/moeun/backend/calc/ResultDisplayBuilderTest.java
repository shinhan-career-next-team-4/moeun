package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moeun.backend.calc.application.Formatter;
import com.moeun.backend.calc.application.ResultDisplayBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공유 display 맵 빌더(계약 internal-calc C2-6, planContext C1a-6, G-5a, G-8a, G-8b).
 * 기대값은 계약 부록 A의 fixture(docs/api/examples/plan-context/*.json)의 display 객체다.
 */
class ResultDisplayBuilderTest {

    private static final Path FIXTURES = Path.of("../docs/api/examples/plan-context");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YearMonth START = YearMonth.of(2026, 11);

    private final ResultDisplayBuilder builder = new ResultDisplayBuilder(new Formatter(FormatterTest.COPY));

    private static JsonNode readFixture(String name) {
        try {
            return JSON.readTree(Files.readString(FIXTURES.resolve(name + ".json")));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read fixture " + name, exception);
        }
    }

    private static CalcInput toInput(JsonNode conditions, JsonNode result) {
        return new CalcInput(conditions.get("goalAmount").asLong(), conditions.get("targetPeriodMonths").asInt(),
                YearMonth.parse(result.get("calcStartMonth").asString()), conditions.get("heldAssets").asLong(),
                conditions.get("monthlySavingAmount").asLong(),
                new BigDecimal(conditions.get("savingsRateInputPct").asString()),
                conditions.get("savingsRatioPct").asInt(), Scenario.valueOf(result.get("baselineScenario").asString()));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.asString());
    }

    private static CalcResult toResult(JsonNode result) {
        List<ScenarioResult> scenarios = new ArrayList<>();
        for (JsonNode scenario : result.get("scenarios")) {
            JsonNode reach = scenario.get("goalReachMonth");
            scenarios.add(new ScenarioResult(Scenario.valueOf(scenario.get("scenario").asString()), null,
                    scenario.get("afterTaxAsset").asLong(), scenario.get("principal").asLong(),
                    scenario.get("afterTaxGain").asLong(), scenario.get("gapToGoal").asLong(),
                    scenario.get("isAchieved").asBoolean(),
                    reach.isNull() ? null : YearMonth.parse(reach.asString()), List.of()));
        }
        JsonNode assumptions = result.get("assumptions");
        JsonNode baseMonth = assumptions.get("savingsRateBaseMonth");
        JsonNode sp500Date = assumptions.get("sp500BaseDate");
        JsonNode method = assumptions.get("scenarioMethod");
        List<ExcludedItem> excluded = new ArrayList<>();
        assumptions.get("excludedItems").forEach(item -> excluded.add(ExcludedItem.valueOf(item.asString())));
        Assumptions parsed = new Assumptions(new BigDecimal(assumptions.get("savingsRatePct").asString()),
                assumptions.get("isDefaultSavingsRate").asBoolean(),
                baseMonth.isNull() ? null : YearMonth.parse(baseMonth.asString()),
                assumptions.get("savingsRatioPct").asInt(), new BigDecimal(assumptions.get("taxRatePct").asString()),
                method.isNull() ? null : ScenarioMethod.valueOf(method.asString()),
                sp500Date.isNull() ? null : LocalDate.parse(sp500Date.asString()), excluded,
                textOrNull(assumptions.get("etfProductCode")), textOrNull(assumptions.get("etfProductName")),
                decimalOrNull(assumptions.get("etfGainTaxRatePct")));
        JsonNode targetMonth = result.get("targetMonth");
        return new CalcResult(CalcStatus.valueOf(result.get("status").asString()), result.get("goalAmount").asLong(),
                YearMonth.parse(result.get("calcStartMonth").asString()),
                targetMonth.isNull() ? null : YearMonth.parse(targetMonth.asString()),
                Scenario.valueOf(result.get("baselineScenario").asString()), scenarios, null, parsed,
                new AppliedDataVersions(1L, 2L, 3L, "test"));
    }

    private static Map<String, String> toMap(JsonNode display) {
        Map<String, String> map = new LinkedHashMap<>();
        display.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asString()));
        return map;
    }

    private static ScenarioResult createScenario(Scenario scenario, long afterTaxAsset, boolean isAchieved,
            YearMonth goalReachMonth) {
        return new ScenarioResult(scenario, null, afterTaxAsset, 15_800_000L, afterTaxAsset - 15_800_000L,
                afterTaxAsset - 20_000_000L, isAchieved, goalReachMonth, List.of());
    }

    private static CalcResult createResult(CalcStatus status, List<ScenarioResult> scenarios) {
        Assumptions assumptions = new Assumptions(new BigDecimal("2.84"), false, null, 50, new BigDecimal("15.4"),
                null, null, List.of());
        return new CalcResult(status, 20_000_000L, START, START.plusMonths(36), Scenario.AVG, scenarios, null,
                assumptions, new AppliedDataVersions(null, null, 1L, "test"));
    }

    private static CalcInput createInput() {
        return new CalcInput(20_000_000L, 36, START, 5_000_000L, 300_000L, new BigDecimal("2.84"), 50, Scenario.AVG);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plan-context-basic", "plan-context-etf0", "plan-context-exceeded",
        "plan-context-achieved", "plan-context-zero-held"})
    @DisplayName("계약 부록 A의 raw 값으로 만든 결과의 display가 fixture와 글자까지 같다(G-5a)")
    void matchesContractFixtures(String fixture) {
        JsonNode root = PlanContextSerializationTest.withEtfDisplayKeys(readFixture(fixture));
        JsonNode result = root.get("result");

        Map<String, String> display = builder.build(toInput(root.get("conditions"), result), toResult(result),
                "", "conditions.");

        assertThat(display).containsExactlyInAnyOrderEntriesOf(
                toMap(root.get("display")));
    }

    @Test
    @DisplayName("접두어 current.·conditions.current.를 주면 같은 규칙으로 키 앞에 붙는다(/internal/calc C2-6)")
    void appliesSidePrefixes() {
        CalcResult result = createResult(CalcStatus.CALCULATED,
                List.of(createScenario(Scenario.AVG, 17_120_000L, false, null)));

        Map<String, String> display = builder.build(createInput(), result, "current.", "conditions.current.");

        assertThat(display).containsEntry("current.AVG.afterTaxAsset", "약 1,712만원")
                .containsEntry("current.targetMonth", "2029년 11월")
                .containsEntry("conditions.current.monthlySavingAmount", "약 30만원")
                .containsEntry("goalAmount", "약 2,000만원")
                .doesNotContainKey("AVG.afterTaxAsset");
        Map<String, String> changed = builder.build(createInput(), result, "changed.", "conditions.changed.");
        assertThat(changed).containsEntry("changed.AVG.gapToGoal", "-약 288만원 미달")
                .containsEntry("conditions.changed.targetPeriodMonths", "3년 0개월");
    }

    @Test
    @DisplayName("비교 접두어에서는 C2-6에 없는 키(시작월·보유 자산·가정)를 만들지 않는다")
    void omitsPlanContextOnlyKeysForComparison() {
        CalcResult result = createResult(CalcStatus.CALCULATED,
                List.of(createScenario(Scenario.AVG, 17_120_000L, false, null)));

        Map<String, String> display = builder.build(createInput(), result, "current.", "conditions.current.");

        assertThat(display.keySet()).noneMatch(key -> key.contains("calcStartMonth") || key.contains("heldAssets")
                || key.contains("assumptions."));
    }

    @Test
    @DisplayName("목표월·월 저축액·기간이 없으면 그 키를 만들지 않는다")
    void omitsKeysForMissingValues() {
        CalcInput noPeriodNoSaving = new CalcInput(20_000_000L, null, START, 5_000_000L, null,
                new BigDecimal("2.84"), 50, Scenario.AVG);
        CalcResult noTarget = new CalcResult(CalcStatus.ALREADY_EXCEEDED, 20_000_000L, START, null, Scenario.AVG,
                List.of(), null, new Assumptions(new BigDecimal("2.84"), false, null, 50, new BigDecimal("15.4"),
                        null, null, List.of()), new AppliedDataVersions(null, null, 1L, "test"));

        Map<String, String> display = builder.build(noPeriodNoSaving, noTarget, "", "conditions.");

        assertThat(display).doesNotContainKeys("targetMonth", "conditions.monthlySavingAmount",
                "conditions.targetPeriodMonths");
    }

    @Test
    @DisplayName("중간에 목표에 닿았다가 목표월에 내려온 BAD(미달, raw 도달 시점 있음)는 goalReachMonth 키가 없다(G-8a)")
    void omitsReachMonthWhenMissedEvenIfReachedMidway() {
        CalcResult result = createResult(CalcStatus.CALCULATED, List.of(
                createScenario(Scenario.GOOD, 21_000_000L, true, YearMonth.of(2029, 5)),
                createScenario(Scenario.AVG, 20_500_000L, true, YearMonth.of(2029, 9)),
                createScenario(Scenario.BAD, 19_620_000L, false, YearMonth.of(2029, 5))));

        Map<String, String> display = builder.build(createInput(), result, "", "conditions.");

        assertThat(display).containsEntry("GOOD.goalReachMonth", "2029년 5월")
                .containsEntry("AVG.goalReachMonth", "2029년 9월")
                .doesNotContainKey("BAD.goalReachMonth")
                .containsEntry("BAD.gapToGoal", "-약 38만원 미달");
    }

    @Test
    @DisplayName("CALCULATED 결과면 planContext 요구 키가 모두 있다(G-5a 불변식)")
    void containsRequiredKeysForCalculatedResult() {
        CalcResult result = createResult(CalcStatus.CALCULATED, List.of(
                createScenario(Scenario.GOOD, 21_000_000L, true, YearMonth.of(2029, 5)),
                createScenario(Scenario.AVG, 19_000_000L, false, null),
                createScenario(Scenario.BAD, 17_000_000L, false, null)));

        Map<String, String> display = builder.build(createInput(), result, "", "conditions.");

        assertThat(display).containsKeys("goalAmount", "calcStartMonth", "targetMonth", "conditions.heldAssets",
                "conditions.monthlySavingAmount", "conditions.targetPeriodMonths", "conditions.savingsRatioPct",
                "conditions.savingsRateInputPct", "assumptions.taxRatePct", "GOOD.goalReachMonth");
        for (String scenario : List.of("GOOD", "AVG", "BAD")) {
            assertThat(display).containsKeys(scenario + ".afterTaxAsset", scenario + ".principal",
                    scenario + ".afterTaxGain", scenario + ".gapToGoal");
        }
        assertThat(display).doesNotContainKeys("assumptions.savingsRateBaseMonth", "assumptions.sp500BaseDate");
    }

    @Test
    @DisplayName("ALREADY_EXCEEDED면 goalAmount·conditions.heldAssets가 있고 시나리오 키는 없다(G-5a 불변식)")
    void containsRequiredKeysForExceededResult() {
        Map<String, String> display = builder.build(createInput(), createResult(CalcStatus.ALREADY_EXCEEDED,
                List.of()), "", "conditions.");

        assertThat(display).containsKeys("goalAmount", "conditions.heldAssets");
        assertThat(display.keySet()).noneMatch(key -> key.startsWith("AVG.") || key.startsWith("GOOD."));
    }

    @Test
    @DisplayName("시나리오마다 달성/미달 라벨이 raw isAchieved와 같다(G-5a)")
    void keepsLabelsConsistentWithIsAchieved() {
        CalcResult result = createResult(CalcStatus.CALCULATED, List.of(
                createScenario(Scenario.GOOD, 20_000_000L, true, YearMonth.of(2029, 11)),
                createScenario(Scenario.AVG, 19_997_000L, false, null),
                createScenario(Scenario.BAD, 15_000_000L, false, null)));

        Map<String, String> display = builder.build(createInput(), result, "", "conditions.");

        for (ScenarioResult scenario : result.scenarios()) {
            String label = scenario.isAchieved() ? "달성" : "미달";
            assertThat(display.get(scenario.scenario() + ".gapToGoal")).endsWith(label);
        }
        assertThat(display).containsEntry("GOOD.gapToGoal", "0원 달성").containsEntry("AVG.gapToGoal", "-1만원 미만 미달");
    }

    @Test
    @DisplayName("ETF 종목이 있으면 가정 보기에 종목 이름과 종목 세율이 나오고, 비과세는 이유가 붙는다(#150)")
    void showsEtfProductAndTaxRate() {
        Assumptions assumptions = new Assumptions(new BigDecimal("2.84"), true, YearMonth.of(2026, 10), 50,
                new BigDecimal("15.4"), ScenarioMethod.ROLLING_DISTRIBUTION, LocalDate.of(2026, 10, 31), List.of(),
                "069500", "코스피200 추종 ETF", BigDecimal.ZERO);
        CalcResult result = new CalcResult(CalcStatus.CALCULATED, 20_000_000L, START, START.plusMonths(36),
                Scenario.AVG, List.of(createScenario(Scenario.AVG, 17_000_000L, false, null)), null, assumptions,
                new AppliedDataVersions(1L, 2L, 3L, "test"));

        Map<String, String> display = builder.build(createInput(), result, "", "conditions.");

        assertThat(display).containsEntry("assumptions.etfProductName", "코스피200 추종 ETF")
                .containsEntry("assumptions.etfGainTaxRatePct", "0%(국내 주식형 ETF 매매차익 비과세)")
                .containsEntry("assumptions.taxRatePct", "15.4%");
    }

    @Test
    @DisplayName("비교 응답(접두어가 있는 결과)에는 종목 표시 키를 만들지 않는다")
    void omitsEtfKeysForComparison() {
        Assumptions assumptions = new Assumptions(new BigDecimal("2.84"), false, null, 50, new BigDecimal("15.4"),
                ScenarioMethod.ROLLING_DISTRIBUTION, LocalDate.of(2026, 10, 31), List.of(), "SP500", "S&P500 추종 ETF",
                new BigDecimal("15.4"));
        CalcResult result = new CalcResult(CalcStatus.CALCULATED, 20_000_000L, START, START.plusMonths(36),
                Scenario.AVG, List.of(createScenario(Scenario.AVG, 17_000_000L, false, null)), null, assumptions,
                new AppliedDataVersions(1L, 2L, 3L, "test"));

        Map<String, String> display = builder.build(createInput(), result, "current.", "conditions.current.");

        assertThat(display.keySet()).noneMatch(key -> key.contains("etf"));
    }
}
