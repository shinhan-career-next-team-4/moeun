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
import java.util.Iterator;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * planContext 직렬화(계약 C1a-13 G-5b, C1a-7, C1a-9). 엔진을 돌리지 않고 계약 부록 A의 raw 값으로 만든 입력을
 * {@link PlanContextView}로 직렬화해 fixture 전체와 비교한다. display도 fixture에서 복사하지 않고 공유 빌더로 만든다.
 */
class PlanContextSerializationTest {

    private static final Path FIXTURES = Path.of("../docs/api/examples/plan-context");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ResultDisplayBuilder builder = new ResultDisplayBuilder(new Formatter(FormatterTest.COPY));

    private static JsonNode readFixture(String name) {
        try {
            return JSON.readTree(Files.readString(FIXTURES.resolve(name + ".json")));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read fixture " + name, exception);
        }
    }

    private static YearMonth toMonth(JsonNode node) {
        return node == null || node.isNull() ? null : YearMonth.parse(node.asString());
    }

    private static Long toLong(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static CalcInput toInput(JsonNode conditions, JsonNode result) {
        return new CalcInput(conditions.get("goalAmount").asLong(), conditions.get("targetPeriodMonths").asInt(),
                toMonth(result.get("calcStartMonth")), conditions.get("heldAssets").asLong(),
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
            JsonNode annualReturn = scenario.get("annualReturnPct");
            scenarios.add(new ScenarioResult(Scenario.valueOf(scenario.get("scenario").asString()),
                    annualReturn.isNull() ? null : new BigDecimal(annualReturn.asString()),
                    scenario.get("afterTaxAsset").asLong(), scenario.get("principal").asLong(),
                    scenario.get("afterTaxGain").asLong(), scenario.get("gapToGoal").asLong(),
                    scenario.get("isAchieved").asBoolean(), toMonth(scenario.get("goalReachMonth")),
                    List.of(new MonthlyPoint(toMonth(result.get("calcStartMonth")), 1L, 1L))));
        }
        JsonNode assumptions = result.get("assumptions");
        JsonNode sp500Date = assumptions.get("sp500BaseDate");
        JsonNode method = assumptions.get("scenarioMethod");
        List<ExcludedItem> excluded = new ArrayList<>();
        assumptions.get("excludedItems").forEach(item -> excluded.add(ExcludedItem.valueOf(item.asString())));
        Assumptions parsed = new Assumptions(new BigDecimal(assumptions.get("savingsRatePct").asString()),
                assumptions.get("isDefaultSavingsRate").asBoolean(), toMonth(assumptions.get("savingsRateBaseMonth")),
                assumptions.get("savingsRatioPct").asInt(), new BigDecimal(assumptions.get("taxRatePct").asString()),
                method.isNull() ? null : ScenarioMethod.valueOf(method.asString()),
                sp500Date.isNull() ? null : LocalDate.parse(sp500Date.asString()), excluded,
                textOrNull(assumptions.get("etfProductCode")), textOrNull(assumptions.get("etfProductName")),
                decimalOrNull(assumptions.get("etfGainTaxRatePct")));
        JsonNode versions = result.get("dataVersions");
        AppliedDataVersions dataVersions = new AppliedDataVersions(toLong(versions.get("savingsRateVersionId")),
                toLong(versions.get("sp500VersionId")), versions.get("calcParamVersionId").asLong(),
                versions.get("engineVersion").asString());
        return new CalcResult(CalcStatus.valueOf(result.get("status").asString()), result.get("goalAmount").asLong(),
                toMonth(result.get("calcStartMonth")), toMonth(result.get("targetMonth")),
                Scenario.valueOf(result.get("baselineScenario").asString()), scenarios,
                toLong(result.get("requiredMonthlySaving")), parsed, dataVersions);
    }

    private PlanContextView toView(JsonNode fixture) {
        JsonNode result = fixture.get("result");
        CalcInput input = toInput(fixture.get("conditions"), result);
        CalcResult calcResult = toResult(result);
        Map<String, String> display = builder.build(input, calcResult, "", "conditions.");
        return PlanContextView.of(fixture.get("calcRunId").asLong(), input, calcResult, display);
    }

    /**
     * 종목 표시 키 2개(#150)는 Spring이 더하지만 아직 계약 표(C1a-6)·ai-server 허용 키에 없어 공유 fixture에 넣지 않았다
     * (B팀 합의 뒤 옮김). 그래서 기대값은 fixture에 이 두 키를 더한 것이다.
     */
    static JsonNode withEtfDisplayKeys(JsonNode fixture) {
        ObjectNode copy = (ObjectNode)fixture.deepCopy();
        ObjectNode assumptions = (ObjectNode)copy.get("result").get("assumptions");
        // 가정 필드 3개(#150)도 아직 공유 fixture에 없다(프론트 목·ai-server가 같은 파일을 읽음). 엔진처럼 ETF가 있으면 대표 상품
        if (!assumptions.has("etfProductCode")) {
            boolean hasEtf = !assumptions.get("scenarioMethod").isNull();
            assumptions.put("etfProductCode", hasEtf ? "SP500" : null);
            assumptions.put("etfProductName", hasEtf ? "S&P500 추종 ETF" : null);
            if (hasEtf) {
                assumptions.put("etfGainTaxRatePct", new BigDecimal("15.4"));
            } else {
                assumptions.putNull("etfGainTaxRatePct");
            }
        }
        JsonNode name = assumptions.get("etfProductName");
        if (name != null && !name.isNull()) {
            ObjectNode display = (ObjectNode)copy.get("display");
            display.put("assumptions.etfProductName", name.asString());
            display.put("assumptions.etfGainTaxRatePct",
                    new BigDecimal(assumptions.get("etfGainTaxRatePct").asString()).stripTrailingZeros().toPlainString()
                            + "%");
        }
        return copy;
    }

    /** 숫자는 값으로 비교한다(15.4와 15.40을 같게 본다). 나머지는 키·순서(배열)·값이 모두 같아야 한다. */
    private static void assertSameJson(String path, JsonNode actual, JsonNode expected) {
        if (expected.isNumber()) {
            assertThat(actual.isNumber()).as(path + " is a number").isTrue();
            assertThat(new BigDecimal(actual.asString())).as(path).isEqualByComparingTo(expected.asString());
            return;
        }
        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + " is an object").isTrue();
            assertThat(actual.propertyNames()).as(path + " keys")
                    .containsExactlyInAnyOrderElementsOf(expected.propertyNames());
            expected.properties().forEach(entry ->
                    assertSameJson(path + "." + entry.getKey(), actual.get(entry.getKey()), entry.getValue()));
            return;
        }
        if (expected.isArray()) {
            assertThat(actual.isArray()).as(path + " is an array").isTrue();
            assertThat(actual.size()).as(path + " size").isEqualTo(expected.size());
            Iterator<JsonNode> actualItems = actual.iterator();
            int index = 0;
            for (JsonNode item : expected) {
                assertSameJson(path + "[" + index++ + "]", actualItems.next(), item);
            }
            return;
        }
        assertThat(actual).as(path).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plan-context-basic", "plan-context-etf0", "plan-context-exceeded",
        "plan-context-achieved", "plan-context-zero-held"})
    @DisplayName("계약 부록 A의 raw 값으로 만든 PlanContextView의 JSON이 fixture 전체와 같다(G-5b)")
    void matchesContractFixtures(String fixture) {
        JsonNode expected = withEtfDisplayKeys(readFixture(fixture));

        JsonNode actual = JSON.valueToTree(toView(expected));

        assertSameJson("$", actual, expected);
    }

    @Test
    @DisplayName("최상위는 calcRunId·conditions·result·display 4개이고 계정 식별 키와 월별 점이 없다")
    void exposesOnlyContractKeys() {
        JsonNode actual = JSON.valueToTree(toView(readFixture("plan-context-basic")));

        assertThat(actual.propertyNames()).containsExactlyInAnyOrder("calcRunId", "conditions", "result", "display");
        assertThat(actual.toString()).doesNotContain("memberId", "loginId", "monthlyPoints", "goalType",
                "goalCustomName", "existingMonthlyContribution");
        assertThat(actual.get("result").get("assumptions")).isNotNull();
        assertThat(actual.get("conditions").propertyNames()).containsExactlyInAnyOrder("goalAmount", "heldAssets",
                "monthlySavingAmount", "targetPeriodMonths", "savingsRatioPct", "savingsRateInputPct");
        assertThat(actual.get("result").get("scenarios").get(0).has("isAchieved")).isTrue();
        assertThat(actual.get("result").get("assumptions").has("isDefaultSavingsRate")).isTrue();
    }

    @Test
    @DisplayName("이미 초과면 scenarios는 빈 배열이고 display에 goalAmount·conditions.heldAssets가 있으며 초과 금액 키는 없다")
    void keepsExceededPlanMinimal() {
        PlanContextView view = toView(readFixture("plan-context-exceeded"));

        assertThat(view.result().scenarios()).isEmpty();
        assertThat(view.display()).containsKeys("goalAmount", "conditions.heldAssets");
        assertThat(view.display().keySet()).noneMatch(key -> key.startsWith("GOOD.") || key.startsWith("AVG.")
                || key.startsWith("BAD.") || key.toLowerCase().contains("excess"));
    }

    @Test
    @DisplayName("ETF 0%면 AVG 하나이고 연 수익률·산정 방식·S&P500 기준일·버전이 null, display는 AVG.*만 있다")
    void keepsEtfZeroPlanToAverageOnly() {
        JsonNode actual = JSON.valueToTree(toView(readFixture("plan-context-etf0")));
        JsonNode result = actual.get("result");

        assertThat(result.get("scenarios").size()).isEqualTo(1);
        assertThat(result.get("scenarios").get(0).get("scenario").asString()).isEqualTo("AVG");
        assertThat(result.get("scenarios").get(0).get("annualReturnPct").isNull()).isTrue();
        assertThat(result.get("assumptions").get("scenarioMethod").isNull()).isTrue();
        assertThat(result.get("assumptions").get("sp500BaseDate").isNull()).isTrue();
        assertThat(result.get("dataVersions").get("sp500VersionId").isNull()).isTrue();
        assertThat(actual.get("display").propertyNames()).noneMatch(key -> key.startsWith("GOOD.")
                || key.startsWith("BAD."));
    }
}
