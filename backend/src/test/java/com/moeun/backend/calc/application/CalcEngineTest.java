package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.ExcludedItem;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;

/**
 * 적금만(ETF 0%) 계산(#26). 기대값은 docs/calc-spec.md를 손으로 계산한 골든 케이스다.
 */
class CalcEngineTest {

    private static final YearMonth START_MONTH = YearMonth.of(2026, 11);
    private static final int SAVINGS_ONLY_PCT = 100;
    private static final CalcParams PARAMS = new CalcParams(
            new DataVersionInfo(7L, DataKind.CALC_PARAM, "test-v1", LocalDate.of(2026, 11, 17), "테스트"),
            Map.of(CalcParamKey.TAX_RATE_PCT, new BigDecimal("15.4")));

    private final CalcEngine engine = new CalcEngine();

    private static CalcInput createSavingsOnlyInput(int targetPeriodMonths, long heldAssets) {
        return new CalcInput(100_000_000L, targetPeriodMonths, START_MONTH, heldAssets, 300_000L,
                new BigDecimal("2.84"), SAVINGS_ONLY_PCT, Scenario.AVG);
    }

    private static void assertMatchesGolden(String fileName) {
        GoldenCase golden = GoldenCase.read(fileName);

        CalcResult result = new CalcEngine().calculate(golden.input(), golden.params());

        assertThat(result.status()).isEqualTo(golden.expected().status());
        assertThat(result.targetMonth()).isEqualTo(golden.expected().targetMonth());
        assertThat(result.scenarios()).hasSameSizeAs(golden.expected().scenarios());
        for (int index = 0; index < result.scenarios().size(); index++) {
            ScenarioResult actual = result.scenarios().get(index);
            GoldenCase.ExpectedScenario expected = golden.expected().scenarios().get(index);
            assertThat(actual.scenario()).isEqualTo(expected.scenario());
            assertThat(actual.afterTaxAsset()).as(golden.id() + " afterTaxAsset").isEqualTo(expected.afterTaxAsset());
            assertThat(actual.principal()).isEqualTo(expected.principal());
            assertThat(actual.afterTaxGain()).isEqualTo(expected.afterTaxGain());
            assertThat(actual.gapToGoal()).isEqualTo(expected.gapToGoal());
            assertThat(actual.isAchieved()).isEqualTo(expected.isAchieved());
            assertThat(actual.goalReachMonth()).isEqualTo(expected.goalReachMonth());
            assertThat(actual.monthlyPoints()).as(golden.id() + " monthlyPoints")
                    .containsExactlyElementsOf(expected.monthlyPoints());
        }
    }

    @Test
    @DisplayName("골든 G1: 월 10만원·연 3%·12개월 적금의 세후 예상 자산과 월별 점이 원 단위로 같다")
    void matchesGoldenSavingsOnlyMinimal() {
        assertMatchesGolden("g1-savings-only-minimal.json");
    }

    @Test
    @DisplayName("골든 G2: 보유 500만원·월 30만원·3.12%·36개월 적금이 원 단위로 같다")
    void matchesGoldenSavingsOnlyPlan() {
        assertMatchesGolden("g2-savings-only-a1-plan.json");
    }

    @Test
    @DisplayName("골든 G4a: 반올림한 세후 자산이 목표와 같으면(목표 대비 0원) 달성이고 목표월에 도달한다")
    void matchesGoldenGapZeroAchieved() {
        assertMatchesGolden("g4-gap-zero-achieved.json");
    }

    @Test
    @DisplayName("골든 G4b: 목표보다 1원 모자라면 미달이고 도달 시점이 없다")
    void matchesGoldenGapMinusOneMissed() {
        assertMatchesGolden("g4-gap-zero-missed.json");
    }

    @Test
    @DisplayName("중간 달에 목표에 처음 닿으면 그 달이 도달 시점이다")
    void findsFirstMonthReachingGoal() {
        // 보유 0원, 월 30만원: 원금만으로 k=10에 300만원이 되고 이자가 붙어 그 달에 처음 300만원을 넘는다
        CalcInput input = new CalcInput(3_000_000L, 36, START_MONTH, 0L, 300_000L,
                new BigDecimal("2.84"), SAVINGS_ONLY_PCT, Scenario.AVG);

        ScenarioResult scenario = engine.calculate(input, PARAMS).scenarios().get(0);

        assertThat(scenario.isAchieved()).isTrue();
        assertThat(scenario.goalReachMonth()).isEqualTo(START_MONTH.plusMonths(10));
        assertThat(scenario.monthlyPoints().get(9).afterTaxAsset()).isLessThan(3_000_000L);
        assertThat(scenario.monthlyPoints().get(10).afterTaxAsset()).isGreaterThanOrEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("ETF 0% 계획이면 시나리오는 AVG 하나다")
    void returnsOnlyAverageScenarioWithoutEtf() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(36, 0L), PARAMS);

        assertThat(result.scenarios()).extracting(ScenarioResult::scenario).containsExactly(Scenario.AVG);
        assertThat(result.scenarios().get(0).annualReturnPct()).isNull();
    }

    @Test
    @DisplayName("같은 입력과 같은 데이터 버전으로 두 번 계산하면 결과가 같다")
    void returnsSameResultForSameInput() {
        CalcInput input = createSavingsOnlyInput(36, 5_000_000L);

        assertThat(engine.calculate(input, PARAMS)).isEqualTo(engine.calculate(input, PARAMS));
    }

    @Test
    @DisplayName("36개월·시작월 2026-11이면 월별 점이 37개이고 2026-11부터 2029-11까지 한 달씩 오른다")
    void createsOnePointPerMonthFromStartToTarget() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(36, 0L), PARAMS);

        List<MonthlyPoint> points = result.scenarios().get(0).monthlyPoints();
        assertThat(points).hasSize(37);
        for (int k = 0; k < points.size(); k++) {
            assertThat(points.get(k).month()).isEqualTo(START_MONTH.plusMonths(k));
        }
        assertThat(points.get(36).month()).isEqualTo(YearMonth.of(2029, 11));
    }

    @Test
    @DisplayName("첫 점은 계산 시작월이고 값은 운용 수익 없는 보유 자산이다")
    void startsFromHeldAssetsAtStartMonth() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(36, 5_000_000L), PARAMS);

        MonthlyPoint first = result.scenarios().get(0).monthlyPoints().get(0);
        assertThat(first.month()).isEqualTo(START_MONTH);
        assertThat(first.afterTaxAsset()).isEqualTo(5_000_000L);
        assertThat(first.principal()).isEqualTo(5_000_000L);
    }

    @ParameterizedTest(name = "{0}개월이면 점 {0} + 1개")
    @ValueSource(ints = {1, 360})
    @DisplayName("점 수는 목표 기간 + 1이다")
    void createsTargetPeriodPlusOnePoints(int targetPeriodMonths) {
        CalcResult result = engine.calculate(createSavingsOnlyInput(targetPeriodMonths, 0L), PARAMS);

        assertThat(result.scenarios().get(0).monthlyPoints()).hasSize(targetPeriodMonths + 1);
    }

    @Test
    @DisplayName("결과의 마지막 점은 시나리오 결과와 같고, 가정·데이터 버전에 쓴 세율과 엔진 버전이 남는다")
    void recordsAssumptionsAndVersions() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(12, 0L), PARAMS);

        ScenarioResult scenario = result.scenarios().get(0);
        MonthlyPoint last = scenario.monthlyPoints().get(scenario.monthlyPoints().size() - 1);
        assertThat(last.afterTaxAsset()).isEqualTo(scenario.afterTaxAsset());
        assertThat(result.assumptions().taxRatePct()).isEqualByComparingTo("15.4");
        assertThat(result.assumptions().scenarioMethod()).isNull();
        assertThat(result.assumptions().isDefaultSavingsRate()).isFalse();
        assertThat(result.assumptions().excludedItems())
                .containsExactly(ExcludedItem.HELD_ASSET_RETURN, ExcludedItem.SAVINGS_REDEPOSIT);
        assertThat(result.dataVersions().calcParamVersionId()).isEqualTo(7L);
        assertThat(result.dataVersions().sp500VersionId()).isNull();
        assertThat(result.dataVersions().engineVersion()).isEqualTo(CalcEngine.ENGINE_VERSION);
    }

    @Test
    @DisplayName("ETF가 들어간 계획은 #27 전까지 계산하지 않고 막는다")
    void rejectsEtfUntilScenarioEngineExists() {
        CalcInput withEtf = new CalcInput(100_000_000L, 36, START_MONTH, 0L, 300_000L,
                new BigDecimal("2.84"), 50, Scenario.AVG);

        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(withEtf, PARAMS));
    }

    @Test
    @DisplayName("기한 없음·월 저축액 모름·이미 목표 초과는 #27 전까지 엉뚱한 숫자 대신 예외로 막는다")
    void rejectsOtherUnsupportedInputs() {
        CalcInput noPeriod = new CalcInput(100_000_000L, null, START_MONTH, 0L, 300_000L,
                new BigDecimal("2.84"), SAVINGS_ONLY_PCT, Scenario.AVG);
        CalcInput unknownSaving = new CalcInput(100_000_000L, 36, START_MONTH, 0L, null,
                new BigDecimal("2.84"), SAVINGS_ONLY_PCT, Scenario.AVG);
        CalcInput exceeded = new CalcInput(10_000_000L, 36, START_MONTH, 10_000_000L, 300_000L,
                new BigDecimal("2.84"), SAVINGS_ONLY_PCT, Scenario.AVG);

        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(noPeriod, PARAMS));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(unknownSaving, PARAMS));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(exceeded, PARAMS));
    }
}
