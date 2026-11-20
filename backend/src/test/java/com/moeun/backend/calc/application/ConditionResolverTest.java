package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 저장된 계획 + 바꾼 조건 → 계산 입력(#25, 계약 C2-8, D-013, D-032).
 */
class ConditionResolverTest {

    private static final long SAVINGS_RATE_VERSION_ID = 11L;
    private static final SavingsAvgRateData DEFAULT_RATE = new SavingsAvgRateData(
            new DataVersionInfo(SAVINGS_RATE_VERSION_ID, DataKind.SAVINGS_RATE, "kfb-2026-10",
                    LocalDate.of(2026, 10, 1), "은행연합회"),
            YearMonth.of(2026, 10), new BigDecimal("2.84"));
    private static final CalcParams PARAMS = new CalcParams(
            new DataVersionInfo(12L, DataKind.CALC_PARAM, "v1", LocalDate.of(2026, 11, 17), "팀 결정"),
            Map.of(CalcParamKey.TAX_RATE_PCT, new BigDecimal("15.4"),
                    CalcParamKey.DEFAULT_SAVINGS_RATIO_PCT, new BigDecimal("50")));
    private static final ConditionOverride NO_CHANGE = new ConditionOverride(null, null, null, null, null, null);

    private final ConditionResolver resolver = new ConditionResolver();

    /** 3월에 처음 저장한 계획. 금리·비중은 인자로 정한다(null = 사용자가 비움). */
    private static PlanView createPlan(BigDecimal savingsRateInputPct, Integer savingsRatioPct) {
        return new PlanView(101L, GoalType.HOUSE, null, 30_000_000L, 36, 5_000_000L, 300_000L,
                savingsRateInputPct, savingsRatioPct, "2026-03", "AVG", 3L, false,
                Instant.parse("2026-05-20T00:00:00Z"));
    }

    @Test
    @DisplayName("3월 시작월 계획을 5월에 대화·비교로 다시 계산해도 계산 시작월은 3월이다")
    void keepsPlanStartMonthWhenRecalculatingLater() {
        PlanView plan = createPlan(null, null);

        ConditionResolver.ResolvedConditions current = resolver.resolve(plan, DEFAULT_RATE, PARAMS);
        ConditionResolver.ResolvedConditions changed = resolver.resolveChanged(plan,
                new ConditionOverride(500_000L, 60, null, null, null, null), DEFAULT_RATE, PARAMS);

        assertThat(current.input().calcStartMonth()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(changed.input().calcStartMonth()).isEqualTo(YearMonth.of(2026, 3));
    }

    @Test
    @DisplayName("적금 금리를 비운 계획이면 기본금리(은행연합회 평균)를 쓰고 기본금리 사용 여부·기준월·버전을 남긴다")
    void appliesDefaultSavingsRateWhenEmpty() {
        ConditionResolver.ResolvedConditions resolved = resolver.resolve(createPlan(null, 50), DEFAULT_RATE, PARAMS);

        assertThat(resolved.input().savingsRatePct()).isEqualByComparingTo("2.84");
        assertThat(resolved.isDefaultSavingsRate()).isTrue();
        assertThat(resolved.savingsRateBaseMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(resolved.savingsRateVersionId()).isEqualTo(SAVINGS_RATE_VERSION_ID);
    }

    @Test
    @DisplayName("적금 금리를 입력한 계획이면 그 금리를 쓰고 기본금리 정보는 비운다")
    void usesEnteredSavingsRate() {
        ConditionResolver.ResolvedConditions resolved =
                resolver.resolve(createPlan(new BigDecimal("3.5"), 50), DEFAULT_RATE, PARAMS);

        assertThat(resolved.input().savingsRatePct()).isEqualByComparingTo("3.5");
        assertThat(resolved.isDefaultSavingsRate()).isFalse();
        assertThat(resolved.savingsRateBaseMonth()).isNull();
        assertThat(resolved.savingsRateVersionId()).isNull();
    }

    @Test
    @DisplayName("비중을 입력하지 않은 계획이면 적금 5 : ETF 5다(기본 비중 파라미터)")
    void appliesDefaultRatioWhenEmpty() {
        CalcInput input = resolver.resolve(createPlan(null, null), DEFAULT_RATE, PARAMS).input();

        assertThat(input.savingsRatioPct()).isEqualTo(50);
        assertThat(input.etfRatioPct()).isEqualTo(50);
    }

    @Test
    @DisplayName("계획의 나머지 값과 기준 시나리오를 그대로 옮긴다")
    void copiesPlanValues() {
        CalcInput input = resolver.resolve(createPlan(new BigDecimal("3.5"), 70), DEFAULT_RATE, PARAMS).input();

        assertThat(input.goalAmount()).isEqualTo(30_000_000L);
        assertThat(input.targetPeriodMonths()).isEqualTo(36);
        assertThat(input.heldAssets()).isEqualTo(5_000_000L);
        assertThat(input.monthlySavingAmount()).isEqualTo(300_000L);
        assertThat(input.savingsRatioPct()).isEqualTo(70);
        assertThat(input.baselineScenario()).isEqualTo(Scenario.AVG);
    }

    @Test
    @DisplayName("월 저축액만 바꾸면 비중은 현재 계획의 해석된 비중을 그대로 쓴다(방어 규칙, C2-8)")
    void keepsResolvedRatioWhenRatioIsNotChanged() {
        PlanView plan = createPlan(null, null);

        ConditionResolver.ResolvedConditions current = resolver.resolve(plan, DEFAULT_RATE, PARAMS);
        ConditionResolver.ResolvedConditions changed = resolver.resolveChanged(plan,
                new ConditionOverride(500_000L, null, null, null, null, null), DEFAULT_RATE, PARAMS);

        assertThat(changed.input().monthlySavingAmount()).isEqualTo(500_000L);
        assertThat(changed.input().savingsRatioPct()).isEqualTo(current.input().savingsRatioPct());
    }

    @Test
    @DisplayName("계획 비중이 기본값과 달라도(70) 월 저축액만 바꾸면 70이 유지된다")
    void keepsEnteredRatioWhenRatioIsNotChanged() {
        ConditionResolver.ResolvedConditions changed = resolver.resolveChanged(createPlan(null, 70),
                new ConditionOverride(500_000L, null, null, null, null, null), DEFAULT_RATE, PARAMS);

        assertThat(changed.input().savingsRatioPct()).isEqualTo(70);
    }

    @Test
    @DisplayName("계획에 금리가 있으면 기본금리 데이터가 없어도 계산한다")
    void doesNotNeedDefaultRateWhenRateIsEntered() {
        ConditionResolver.ResolvedConditions resolved =
                resolver.resolve(createPlan(new BigDecimal("3.5"), 50), null, PARAMS);

        assertThat(resolved.input().savingsRatePct()).isEqualByComparingTo("3.5");
        assertThat(resolved.isDefaultSavingsRate()).isFalse();
    }

    @Test
    @DisplayName("바꾼 항목만 덮어쓰고 나머지는 현재 계획 값이다. 금리를 안 바꾸면 현재처럼 기본금리다")
    void overridesOnlyChangedFields() {
        PlanView plan = createPlan(null, 60);

        ConditionResolver.ResolvedConditions changed = resolver.resolveChanged(plan,
                new ConditionOverride(null, 48, 30, null, null, null), DEFAULT_RATE, PARAMS);

        assertThat(changed.input().targetPeriodMonths()).isEqualTo(48);
        assertThat(changed.input().savingsRatioPct()).isEqualTo(30);
        assertThat(changed.input().monthlySavingAmount()).isEqualTo(300_000L);
        assertThat(changed.input().goalAmount()).isEqualTo(30_000_000L);
        assertThat(changed.input().savingsRatePct()).isEqualByComparingTo("2.84");
        assertThat(changed.isDefaultSavingsRate()).isTrue();
    }

    @Test
    @DisplayName("금리를 바꾸면 그 금리를 쓰고 기본금리가 아니다")
    void usesChangedSavingsRate() {
        ConditionResolver.ResolvedConditions changed = resolver.resolveChanged(createPlan(null, null),
                new ConditionOverride(null, null, null, new BigDecimal("4.1"), null, null), DEFAULT_RATE, PARAMS);

        assertThat(changed.input().savingsRatePct()).isEqualByComparingTo("4.1");
        assertThat(changed.isDefaultSavingsRate()).isFalse();
        assertThat(changed.savingsRateVersionId()).isNull();
    }

    @Test
    @DisplayName("바꾼 조건이 하나도 없으면 현재 계획 해석과 같다")
    void equalsCurrentWithoutChanges() {
        PlanView plan = createPlan(new BigDecimal("3.5"), 40);

        assertThat(resolver.resolveChanged(plan, NO_CHANGE, DEFAULT_RATE, PARAMS))
                .isEqualTo(resolver.resolve(plan, DEFAULT_RATE, PARAMS));
    }

    @Test
    @DisplayName("저장된 계획에 필수 값이 비어 있으면 계산하지 않고 이유가 담긴 예외로 막는다")
    void rejectsPlanWithMissingRequiredValue() {
        PlanView broken = new PlanView(101L, GoalType.HOUSE, null, null, 36, 5_000_000L, 300_000L,
                null, null, "2026-03", "AVG", 3L, false, Instant.parse("2026-05-20T00:00:00Z"));

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(broken, DEFAULT_RATE, PARAMS))
                .withMessageContaining("goalAmount");
    }

    @Test
    @DisplayName("금리를 비웠는데 기본금리 데이터가 없으면 계산하지 않고 막는다")
    void rejectsMissingDefaultRateWhenNeeded() {
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(createPlan(null, null), null, PARAMS));
    }
}
