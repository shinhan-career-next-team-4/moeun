package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.ExcludedItem;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioMethod;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.Sp500Series;

/**
 * 계산 엔진(#26 적금, #27 ETF 3구간·판정·초과). 기대값은 docs/calc-spec.md를 손으로 계산한 골든 케이스다.
 */
class CalcEngineTest {

    private static final YearMonth START_MONTH = YearMonth.of(2026, 11);
    private static final int SAVINGS_ONLY_PCT = 100;
    private static final int HALF_PCT = 50;
    private static final CalcParams PARAMS = GoldenCase.createParams(new BigDecimal("15.4"));
    /** 레포의 테스트용 가짜 S&P500 121개월(실제 값 아님). 실제 데이터는 이용 조건 때문에 레포에 없다. */
    private static final Sp500Series SP500 = GoldenCase.readTestSp500Series();

    private final CalcEngine engine = new CalcEngine();

    private static CalcInput createSavingsOnlyInput(int targetPeriodMonths, long heldAssets) {
        return new CalcInput(100_000_000L, targetPeriodMonths, START_MONTH, heldAssets, 300_000L,
                new BigDecimal("2.84"), SAVINGS_ONLY_PCT, Scenario.AVG);
    }

    private static CalcInput createInput(long goalAmount, int targetPeriodMonths, long heldAssets,
            int savingsRatioPct) {
        return new CalcInput(goalAmount, targetPeriodMonths, START_MONTH, heldAssets, 300_000L,
                new BigDecimal("2.84"), savingsRatioPct, Scenario.AVG);
    }

    private static void assertMatchesGolden(String fileName) {
        GoldenCase golden = GoldenCase.read(fileName);

        CalcResult result = new CalcEngine().calculate(golden.input(), golden.params(), golden.sp500());

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

        ScenarioResult scenario = engine.calculate(input, PARAMS, SP500).scenarios().get(0);

        assertThat(scenario.isAchieved()).isTrue();
        assertThat(scenario.goalReachMonth()).isEqualTo(START_MONTH.plusMonths(10));
        assertThat(scenario.monthlyPoints().get(9).afterTaxAsset()).isLessThan(3_000_000L);
        assertThat(scenario.monthlyPoints().get(10).afterTaxAsset()).isGreaterThanOrEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("ETF 0% 계획이면 시나리오는 AVG 하나다")
    void returnsOnlyAverageScenarioWithoutEtf() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(36, 0L), PARAMS, SP500);

        assertThat(result.scenarios()).extracting(ScenarioResult::scenario).containsExactly(Scenario.AVG);
        assertThat(result.scenarios().get(0).annualReturnPct()).isNull();
    }

    @Test
    @DisplayName("같은 입력과 같은 데이터 버전으로 두 번 계산하면 결과가 같다")
    void returnsSameResultForSameInput() {
        CalcInput input = createSavingsOnlyInput(36, 5_000_000L);

        assertThat(engine.calculate(input, PARAMS, SP500)).isEqualTo(engine.calculate(input, PARAMS, SP500));
    }

    @Test
    @DisplayName("36개월·시작월 2026-11이면 월별 점이 37개이고 2026-11부터 2029-11까지 한 달씩 오른다")
    void createsOnePointPerMonthFromStartToTarget() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(36, 0L), PARAMS, SP500);

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
        CalcResult result = engine.calculate(createSavingsOnlyInput(36, 5_000_000L), PARAMS, SP500);

        MonthlyPoint first = result.scenarios().get(0).monthlyPoints().get(0);
        assertThat(first.month()).isEqualTo(START_MONTH);
        assertThat(first.afterTaxAsset()).isEqualTo(5_000_000L);
        assertThat(first.principal()).isEqualTo(5_000_000L);
    }

    @ParameterizedTest(name = "{0}개월이면 점 {0} + 1개")
    @ValueSource(ints = {1, 360})
    @DisplayName("점 수는 목표 기간 + 1이다")
    void createsTargetPeriodPlusOnePoints(int targetPeriodMonths) {
        CalcResult result = engine.calculate(createSavingsOnlyInput(targetPeriodMonths, 0L), PARAMS, SP500);

        assertThat(result.scenarios().get(0).monthlyPoints()).hasSize(targetPeriodMonths + 1);
    }

    @Test
    @DisplayName("결과의 마지막 점은 시나리오 결과와 같고, 가정·데이터 버전에 쓴 세율과 엔진 버전이 남는다")
    void recordsAssumptionsAndVersions() {
        CalcResult result = engine.calculate(createSavingsOnlyInput(12, 0L), PARAMS, SP500);

        ScenarioResult scenario = result.scenarios().get(0);
        MonthlyPoint last = scenario.monthlyPoints().get(scenario.monthlyPoints().size() - 1);
        assertThat(last.afterTaxAsset()).isEqualTo(scenario.afterTaxAsset());
        assertThat(result.assumptions().taxRatePct()).isEqualByComparingTo("15.4");
        assertThat(result.assumptions().scenarioMethod()).isNull();
        assertThat(result.assumptions().isDefaultSavingsRate()).isFalse();
        assertThat(result.assumptions().excludedItems())
                .containsExactly(ExcludedItem.HELD_ASSET_RETURN, ExcludedItem.SAVINGS_REDEPOSIT);
        assertThat(result.dataVersions().calcParamVersionId()).isEqualTo(PARAMS.version().id());
        assertThat(result.dataVersions().sp500VersionId()).isNull();
        assertThat(result.dataVersions().engineVersion()).isEqualTo(CalcEngine.ENGINE_VERSION);
    }

    @Test
    @DisplayName("골든 G3: 보유 자산이 목표와 같으면 ALREADY_EXCEEDED이고 시나리오를 계산하지 않는다")
    void matchesGoldenAlreadyExceeded() {
        assertMatchesGolden("g3-already-exceeded.json");
    }

    @Test
    @DisplayName("골든 G8: 관측 수익률이 모두 1%면 세 구간이 모두 1,242,483원이고 GOOD·AVG·BAD 순서다")
    void matchesGoldenBootstrapConstantReturns() {
        assertMatchesGolden("g8-bootstrap-constant-returns.json");
    }

    @Test
    @DisplayName("ETF가 들어가면 시나리오는 항상 GOOD·AVG·BAD 순서이고 나쁨 ≤ 평균 ≤ 좋음이다")
    void returnsThreeScenariosInFixedOrderWithEtf() {
        CalcResult result = engine.calculate(createInput(30_000_000L, 36, 0L, HALF_PCT), PARAMS, SP500);

        assertThat(result.scenarios()).extracting(ScenarioResult::scenario)
                .containsExactly(Scenario.GOOD, Scenario.AVG, Scenario.BAD);
        assertThat(result.scenarios().get(2).afterTaxAsset())
                .isLessThanOrEqualTo(result.scenarios().get(1).afterTaxAsset());
        assertThat(result.scenarios().get(1).afterTaxAsset())
                .isLessThanOrEqualTo(result.scenarios().get(0).afterTaxAsset());
    }

    @ParameterizedTest(name = "목표 {0}원")
    @ValueSource(longs = {5_000_000L, 10_000_000L, 12_000_000L, 14_000_000L, 30_000_000L})
    @DisplayName("달성인 시나리오는 도달 시점이 있고 목표월 이하이며, 마지막 점은 목표월·시나리오 결과와 같다")
    void keepsReachMonthAndLastPointInvariants(long goalAmount) {
        CalcResult result = engine.calculate(createInput(goalAmount, 36, 0L, HALF_PCT), PARAMS, SP500);

        for (ScenarioResult scenario : result.scenarios()) {
            if (scenario.isAchieved()) {
                assertThat(scenario.goalReachMonth()).isNotNull();
                assertThat(scenario.goalReachMonth().isAfter(result.targetMonth())).isFalse();
            }
            MonthlyPoint last = scenario.monthlyPoints().get(scenario.monthlyPoints().size() - 1);
            assertThat(last.month()).isEqualTo(result.targetMonth());
            assertThat(last.afterTaxAsset()).isEqualTo(scenario.afterTaxAsset());
            assertThat(scenario.monthlyPoints()).hasSize(37);
        }
    }

    @Test
    @DisplayName("ETF가 들어가면 가정에 산정 방식·S&P500 기준일·ETF 미반영 항목이, 버전에 S&P500 버전이 남는다")
    void recordsEtfAssumptionsAndVersions() {
        CalcResult result = engine.calculate(createInput(30_000_000L, 36, 0L, HALF_PCT), PARAMS, SP500);

        assertThat(result.assumptions().scenarioMethod()).isEqualTo(ScenarioMethod.ROLLING_DISTRIBUTION);
        assertThat(result.assumptions().sp500BaseDate()).isEqualTo(SP500.version().baseDate());
        assertThat(result.assumptions().excludedItems()).containsExactly(ExcludedItem.ETF_TOTAL_EXPENSE,
                ExcludedItem.EXCHANGE_RATE, ExcludedItem.ETF_DISTRIBUTION, ExcludedItem.HELD_ASSET_RETURN,
                ExcludedItem.SAVINGS_REDEPOSIT);
        assertThat(result.dataVersions().sp500VersionId()).isEqualTo(SP500.version().id());
        assertThat(result.scenarios()).allSatisfy(scenario -> assertThat(scenario.annualReturnPct()).isNull());
    }

    @Test
    @DisplayName("ETF 100%면 적금이 없으므로 적금 재예치는 미반영 항목에 넣지 않는다")
    void omitsSavingsItemWithoutSavings() {
        CalcResult result = engine.calculate(createInput(30_000_000L, 36, 0L, 0), PARAMS, SP500);

        assertThat(result.assumptions().excludedItems()).doesNotContain(ExcludedItem.SAVINGS_REDEPOSIT);
    }

    @Test
    @DisplayName("회귀: 테스트용 S&P500·시드 20261117·경로 1만 개로 낸 3구간 값이 바뀌지 않는다")
    void keepsSeedFixedRegressionValues() {
        // 손계산 값이 아니라 calc-1.1로 낸 값을 고정한 것이다(calc-spec 7-3). 바뀌면 규칙을 바꾼 것이니 엔진 버전을 올린다
        CalcInput input = new CalcInput(500_000_000L, 36, START_MONTH, 5_000_000L, 300_000L,
                new BigDecimal("2.84"), HALF_PCT, Scenario.AVG);

        CalcResult result = engine.calculate(input, PARAMS, SP500);

        assertThat(result.scenarios()).extracting(ScenarioResult::afterTaxAsset)
                .containsExactly(16_888_763L, 16_709_648L, 16_535_534L);
    }

    @Test
    @DisplayName("ETF가 들어가도 같은 입력·같은 데이터 버전이면 결과가 같다(시드 고정)")
    void returnsSameEtfResultForSameInput() {
        CalcInput input = createInput(30_000_000L, 36, 5_000_000L, HALF_PCT);

        assertThat(engine.calculate(input, PARAMS, SP500)).isEqualTo(engine.calculate(input, PARAMS, SP500));
    }

    @Test
    @DisplayName("보유 자산이 목표보다 많으면 ALREADY_EXCEEDED이고 시나리오도 점도 없다")
    void returnsAlreadyExceededWithoutScenarios() {
        CalcResult result = engine.calculate(createInput(10_000_000L, 36, 12_000_000L, HALF_PCT), PARAMS, SP500);

        assertThat(result.status()).isEqualTo(CalcStatus.ALREADY_EXCEEDED);
        assertThat(result.scenarios()).isEmpty();
        assertThat(result.targetMonth()).isEqualTo(START_MONTH.plusMonths(36));
    }

    @Test
    @DisplayName("조건 해석 결과로 계산하면 기본금리 사용 여부·기준월이 가정 보기에, 금리 버전이 데이터 버전에 남는다")
    void recordsDefaultSavingsRateFromResolvedConditions() {
        ConditionResolver.ResolvedConditions resolved = new ConditionResolver.ResolvedConditions(
                createInput(30_000_000L, 36, 0L, HALF_PCT), true, YearMonth.of(2026, 10), 11L);

        CalcResult result = engine.calculate(resolved, PARAMS, SP500);

        assertThat(result.assumptions().isDefaultSavingsRate()).isTrue();
        assertThat(result.assumptions().savingsRateBaseMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(result.dataVersions().savingsRateVersionId()).isEqualTo(11L);
        assertThat(result.scenarios()).isEqualTo(engine.calculate(resolved.input(), PARAMS, SP500).scenarios());
    }

    @Test
    @DisplayName("사용자가 넣은 금리면 기본금리 정보가 비고, 데이터 버전만 미리 구해도 계산 결과의 버전과 같다")
    void determinesSameVersionsWithoutRunningBootstrap() {
        ConditionResolver.ResolvedConditions entered = new ConditionResolver.ResolvedConditions(
                createInput(30_000_000L, 36, 0L, HALF_PCT), false, null, null);
        ConditionResolver.ResolvedConditions exceeded = new ConditionResolver.ResolvedConditions(
                createInput(10_000_000L, 36, 12_000_000L, HALF_PCT), true, YearMonth.of(2026, 10), 11L);
        ConditionResolver.ResolvedConditions savingsOnly = new ConditionResolver.ResolvedConditions(
                createSavingsOnlyInput(36, 0L), true, YearMonth.of(2026, 10), 11L);

        for (ConditionResolver.ResolvedConditions resolved : List.of(entered, exceeded, savingsOnly)) {
            assertThat(engine.determineDataVersions(resolved, PARAMS, SP500))
                    .isEqualTo(engine.calculate(resolved, PARAMS, SP500).dataVersions());
        }
        assertThat(engine.calculate(entered, PARAMS, SP500).assumptions().isDefaultSavingsRate()).isFalse();
        assertThat(engine.determineDataVersions(entered, PARAMS, SP500).savingsRateVersionId()).isNull();
    }

    @Test
    @DisplayName("ETF 계획에 S&P500이 없거나 종가가 모자라면 버전만 구할 때도 계산과 같은 예외로 막는다")
    void rejectsSameInputsAsCalculateWhenDeterminingVersions() {
        ConditionResolver.ResolvedConditions withEtf = new ConditionResolver.ResolvedConditions(
                createInput(30_000_000L, 36, 0L, HALF_PCT), false, null, null);
        Sp500Series tooShort = GoldenCase.createConstantReturnSeries(10, new BigDecimal("0.01"));

        assertThatIllegalArgumentException().isThrownBy(() -> engine.determineDataVersions(withEtf, PARAMS, null));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(withEtf, PARAMS, null));
        assertThatIllegalStateException().isThrownBy(() -> engine.determineDataVersions(withEtf, PARAMS, tooShort));
        assertThatIllegalStateException().isThrownBy(() -> engine.calculate(withEtf, PARAMS, tooShort));
    }

    @Test
    @DisplayName("ETF 계획에 S&P500 데이터가 없으면 이유가 담긴 예외로 막고, ETF 0%면 없어도 계산한다")
    void requiresSp500OnlyForEtfPlans() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.calculate(createInput(30_000_000L, 36, 0L, HALF_PCT), PARAMS, null))
                .withMessageContaining("S&P500");
        assertThat(engine.calculate(createSavingsOnlyInput(36, 0L), PARAMS, null).scenarios()).hasSize(1);
    }

    @Test
    @DisplayName("기한 없음·월 저축액 모름은 해커톤 범위 밖이라 엉뚱한 숫자 대신 예외로 막는다(M-8)")
    void rejectsOutOfScopeInputs() {
        CalcInput noPeriod = new CalcInput(100_000_000L, null, START_MONTH, 0L, 300_000L,
                new BigDecimal("2.84"), HALF_PCT, Scenario.AVG);
        CalcInput unknownSaving = new CalcInput(100_000_000L, 36, START_MONTH, 0L, null,
                new BigDecimal("2.84"), HALF_PCT, Scenario.AVG);

        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(noPeriod, PARAMS, SP500));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.calculate(unknownSaving, PARAMS, SP500));
    }
}
