package com.moeun.backend.plan.domain;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanView;

/** 새 버전 생성 규칙(저장·적용·되돌리기). 스프링 없이 돈다. */
class PlanVersionTest {

    static final long MEMBER_ID = 7L;
    static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant LATER = Instant.parse("2026-12-15T00:00:00Z");

    static PlanVersion firstVersion() {
        return PlanVersion.first(MEMBER_ID, PlanConditions.from(valid().savingsRatioPct(60).build()),
                YearMonth.of(2026, 11), CREATED);
    }

    @Test
    void firstVersionStartsThisMonthWithRepresentativeEtfFromInput() {
        PlanVersion version = firstVersion();

        assertThat(version.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(version.getCalcStartMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(version.getEtfProductCode()).isEqualTo(PlanView.DEFAULT_ETF_PRODUCT_CODE);
        assertThat(version.getSource()).isEqualTo(PlanSource.INPUT);
        assertThat(version.getCreatedAt()).isEqualTo(CREATED);
        assertThat(version.getConditions().goalAmount()).isEqualTo(50_000_000L);
        assertThat(version.getConditions().savingsRatioPct()).isEqualTo(60);
    }

    @Test
    void reviseKeepsStartMonthAndEtfButTakesNewInputAsIs() {
        PlanVersion first = firstVersion().applyOverride(new ConditionOverride(null, null, null, null, null, null),
                "069500", PlanSource.CHAT, CREATED);

        PlanVersion revised = first.revise(PlanConditions.from(valid().monthlySavingAmount(1_500_000L).build()), LATER);

        assertThat(revised.getCalcStartMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(revised.getEtfProductCode()).isEqualTo("069500");
        assertThat(revised.getSource()).isEqualTo(PlanSource.INPUT);
        assertThat(revised.getCreatedAt()).isEqualTo(LATER);
        assertThat(revised.getConditions().monthlySaving()).isEqualTo(1_500_000L);
        assertThat(revised.getConditions().savingsRatioPct()).as("비중은 현재 버전에서 복사하지 않는다(D-086)").isNull();
    }

    @Test
    void applyOverrideReplacesOnlyChangedValues() {
        PlanVersion current = firstVersion();

        PlanVersion applied = current.applyOverride(
                new ConditionOverride(1_500_000L, null, null, new BigDecimal("3.5"), null, null), null,
                PlanSource.COMPARE, LATER);

        PlanConditions conditions = applied.getConditions();
        assertThat(conditions.monthlySaving()).isEqualTo(1_500_000L);
        assertThat(conditions.savingsRatePct()).isEqualByComparingTo("3.5");
        assertThat(conditions.goalAmount()).isEqualTo(50_000_000L);
        assertThat(conditions.targetPeriodMonths()).isEqualTo(36);
        assertThat(conditions.heldAssets()).isEqualTo(6_000_000L);
        assertThat(conditions.savingsRatioPct()).isEqualTo(60);
        assertThat(conditions.goalType()).isEqualTo(GoalType.HOUSE);
        assertThat(applied.getEtfProductCode()).isEqualTo(PlanView.DEFAULT_ETF_PRODUCT_CODE);
        assertThat(applied.getCalcStartMonth()).isEqualTo(current.getCalcStartMonth());
        assertThat(applied.getSource()).isEqualTo(PlanSource.COMPARE);
        assertThat(applied.getCreatedAt()).isEqualTo(LATER);
    }

    @Test
    void applyOverrideChangesRatioAndEtfWhenGiven() {
        PlanVersion applied = firstVersion().applyOverride(
                new ConditionOverride(null, null, 30, null, null, null), "133690", PlanSource.ALTERNATIVE, LATER);

        assertThat(applied.getConditions().savingsRatioPct()).isEqualTo(30);
        assertThat(applied.getEtfProductCode()).isEqualTo("133690");
    }

    @Test
    void copyForUndoCopiesEverythingButCreatedAt() {
        PlanVersion applied = firstVersion().applyOverride(
                new ConditionOverride(1_500_000L, null, null, null, null, null), "069500", PlanSource.CHAT, CREATED);

        PlanVersion copy = applied.copyForUndo(LATER);

        assertThat(copy).isNotSameAs(applied);
        assertThat(copy.getId()).isNull();
        assertThat(copy.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(copy.getConditions()).isEqualTo(applied.getConditions());
        assertThat(copy.getCalcStartMonth()).isEqualTo(applied.getCalcStartMonth());
        assertThat(copy.getEtfProductCode()).isEqualTo("069500");
        assertThat(copy.getSource()).as("되돌리기 복사본은 원본의 경로를 그대로 쓴다").isEqualTo(PlanSource.CHAT);
        assertThat(copy.getCreatedAt()).isEqualTo(LATER);
    }

    @Test
    void conditionsKeepCustomNameOnlyForCustomGoalAndStripIt() {
        PlanConditions custom = PlanConditions.from(valid().goalType(GoalType.CUSTOM).goalCustomName("  여행  ").build());
        PlanConditions house = PlanConditions.from(valid().goalType(GoalType.HOUSE).goalCustomName("여행").build());

        assertThat(custom.goalCustomName()).isEqualTo("여행");
        assertThat(house.goalCustomName()).isNull();
    }

    @Test
    void conditionsRoundTripToInputForValidation() {
        PlanConditions conditions = PlanConditions.from(
                valid().savingsRateInputPct("3.25").savingsRatioPct(40).build());

        assertThat(PlanConditions.from(conditions.toInput())).isEqualTo(conditions);
    }

    @Test
    void applyOverrideWithGoalNameMakesGoalCustom() {
        // 대화로 목표 이름을 바꾸면 결과 화면에서 고칠 때(#341)처럼 직접 입력 목표가 된다(#345, D-081)
        PlanVersion applied = firstVersion().applyOverride(new ConditionOverride(null, null, null, null, null, null),
                null, "  가게 보증금 ", PlanSource.CHAT, LATER);

        assertThat(applied.getConditions().goalType()).isEqualTo(GoalType.CUSTOM);
        assertThat(applied.getConditions().goalCustomName()).isEqualTo("가게 보증금");
        assertThat(applied.getConditions().goalAmount()).isEqualTo(50_000_000L);
        assertThat(applied.getEtfProductCode()).isEqualTo(PlanView.DEFAULT_ETF_PRODUCT_CODE);
    }

    @Test
    void applyOverrideWithoutGoalNameKeepsGoal() {
        PlanVersion applied = firstVersion().applyOverride(new ConditionOverride(400_000L, null, null, null, null,
                null), null, null, PlanSource.CHAT, LATER);

        assertThat(applied.getConditions().goalType()).isEqualTo(GoalType.HOUSE);
        assertThat(applied.getConditions().goalCustomName()).isNull();
    }
}
