package com.moeun.backend.plan;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.AssertablePublishedEvents;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.application.PlanConflictException;
import com.moeun.backend.plan.application.PlanService;
import com.moeun.backend.plan.application.PlanValidationException;
import com.moeun.backend.plan.application.PlanView;

@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
class PlanServiceTest {

    @Autowired
    PlanService planService;

    @Autowired
    JdbcClient jdbc;

    long memberId;
    long otherMemberId;

    @BeforeEach
    void createMembers() {
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member").update();
        memberId = insertMember("me");
        otherMemberId = insertMember("other");
    }

    private long insertMember(String loginId) {
        return jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES (?, 'x') RETURNING id")
                .param(loginId).query(Long.class).single();
    }

    @Test
    void firstSaveCreatesVersionAndPlanWithAvgBaselineAndThisMonth() {
        PlanView view = planService.save(memberId, valid().build());

        assertThat(view.calcStartMonth()).isEqualTo(YearMonth.now(ZoneId.of("Asia/Seoul")).toString());
        assertThat(view.baselineScenario()).isEqualTo("AVG");
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.canUndo()).isFalse();
        assertThat(view.goalAmount()).isEqualTo(50_000_000L);
        assertThat(jdbc.sql("SELECT source FROM plan_version WHERE member_id = ?").param(memberId)
                .query(String.class).single()).isEqualTo("INPUT");
    }

    @Test
    void secondSaveKeepsCalcStartMonth() {
        planService.save(memberId, valid().build());
        jdbc.sql("UPDATE plan_version SET calc_start_month = DATE '2026-01-01' WHERE member_id = ?")
                .param(memberId).update();

        PlanView view = planService.save(memberId, valid().monthlySavingAmount(1_500_000L).build());

        assertThat(view.calcStartMonth()).isEqualTo("2026-01");
        assertThat(view.monthlySaving()).isEqualTo(1_500_000L);
    }

    @Test
    void saveStoresNoExistingContributionAndNoRatio() {
        PlanView view = planService.save(memberId, valid().build());

        assertThat(view.savingsRatioPct()).isNull();
        assertThat(jdbc.sql("SELECT existing_monthly_contribution FROM plan_version WHERE member_id = ?")
                .param(memberId).query(Long.class).optional()).isEmpty();
    }

    @Test
    void secondSaveCopiesSavingsRatioFromCurrentVersion() {
        PlanView saved = planService.save(memberId, valid().build());
        PlanView applied = planService.apply(memberId, ChangePath.ALTERNATIVE,
                new ConditionOverride(null, null, 70, null), saved.revision());
        assertThat(applied.savingsRatioPct()).isEqualTo(70);

        PlanView resaved = planService.save(memberId, valid().monthlySavingAmount(1_500_000L).build());

        assertThat(resaved.savingsRatioPct()).isEqualTo(70);
    }

    @Test
    void saveWithErrorsThrowsValidationExceptionAndWritesNothing() {
        assertThatThrownBy(() -> planService.save(memberId, valid().goalAmount(null).build()))
                .isInstanceOf(PlanValidationException.class)
                .extracting(e -> ((PlanValidationException)e).errors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(FieldError.required("goalAmount"));
        assertThat(jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single()).isZero();
    }

    @Test
    void applyCreatesVersionFromCurrentPlusChanges() {
        PlanView saved = planService.save(memberId, valid().build());

        PlanView applied = planService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());

        assertThat(applied.monthlySaving()).isEqualTo(1_500_000L);
        assertThat(applied.goalAmount()).isEqualTo(saved.goalAmount());
        assertThat(applied.targetPeriodMonths()).isEqualTo(saved.targetPeriodMonths());
        assertThat(applied.revision()).isEqualTo(2L);
        assertThat(applied.canUndo()).isTrue();
        assertThat(jdbc.sql("SELECT source FROM plan_version WHERE member_id = ? ORDER BY id DESC LIMIT 1")
                .param(memberId).query(String.class).single()).isEqualTo("COMPARE");
    }

    @Test
    void applyWithInvalidChangeIsValidationError() {
        PlanView saved = planService.save(memberId, valid().build());

        assertThatThrownBy(() -> planService.apply(memberId, ChangePath.ALTERNATIVE,
                new ConditionOverride(null, null, null, new java.math.BigDecimal("4.00")), saved.revision()))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void applyWithStaleRevisionThrowsConflictWithCurrentPlan() {
        PlanView saved = planService.save(memberId, valid().build());
        planService.apply(memberId, ChangePath.CHAT, new ConditionOverride(1_500_000L, null, null, null),
                saved.revision());

        assertThatThrownBy(() -> planService.apply(memberId, ChangePath.CHAT,
                new ConditionOverride(1_600_000L, null, null, null), saved.revision()))
                .isInstanceOf(PlanConflictException.class)
                .satisfies(e -> assertThat(((PlanConflictException)e).current().orElseThrow().monthlySaving())
                        .isEqualTo(1_500_000L));
    }

    @Test
    void undoRestoresPreviousAndClearsIt() {
        PlanView saved = planService.save(memberId, valid().build());
        PlanView applied = planService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());

        PlanView undone = planService.undo(memberId, applied.revision());

        assertThat(undone.monthlySaving()).isEqualTo(1_200_000L);
        assertThat(undone.revision()).isEqualTo(3L);
        assertThat(undone.canUndo()).isFalse();
    }

    @Test
    void secondUndoIsConflict() {
        PlanView saved = planService.save(memberId, valid().build());
        PlanView applied = planService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());
        PlanView undone = planService.undo(memberId, applied.revision());

        assertThatThrownBy(() -> planService.undo(memberId, undone.revision()))
                .isInstanceOf(PlanConflictException.class);
    }

    @Test
    void applyWithoutPlanIsConflict() {
        assertThatThrownBy(() -> planService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), 1L))
                .isInstanceOf(PlanConflictException.class)
                .satisfies(e -> assertThat(((PlanConflictException)e).current()).isEmpty());
    }

    @Test
    void eachChangePublishesPlanChanged(AssertablePublishedEvents events) {
        PlanView saved = planService.save(memberId, valid().build());
        PlanView applied = planService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());
        planService.undo(memberId, applied.revision());

        List<PlanChanged> changes = StreamSupport.stream(events.ofType(PlanChanged.class).spliterator(), false)
                .toList();
        assertThat(changes).extracting(PlanChanged::action).containsExactly("INPUT", "APPLY", "UNDO");
        PlanChanged apply = changes.get(1);
        PlanChanged undo = changes.get(2);
        assertThat(undo.beforeVersionId()).isEqualTo(apply.afterVersionId());
        assertThat(undo.afterVersionId()).isEqualTo(apply.beforeVersionId());
    }

    @Test
    void otherMembersPlanIsInvisible() {
        planService.save(memberId, valid().build());

        assertThat(planService.find(otherMemberId)).isEmpty();
    }
}
