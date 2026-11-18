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

@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
class PlanServiceTest {

    @Autowired
    PlanSaveService saveService;

    @Autowired
    PlanApplyService applyService;

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
        PlanSaveResult result = saveService.save(memberId, valid().build(), null);
        PlanView view = saveService.findCurrent(memberId).orElseThrow();

        assertThat(result.planVersionId()).isEqualTo(view.currentVersionId());
        assertThat(result.revision()).isEqualTo(1L);
        assertThat(view.calcStartMonth()).isEqualTo(YearMonth.now(ZoneId.of("Asia/Seoul")).toString());
        assertThat(view.baselineScenario()).isEqualTo("AVG");
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.canUndo()).isFalse();
        assertThat(view.goalAmount()).isEqualTo(50_000_000L);
        assertThat(jdbc.sql("SELECT source FROM plan_version WHERE member_id = ?").param(memberId)
                .query(String.class).single()).isEqualTo("INPUT");
    }

    @Test
    void secondSaveKeepsCalcStartMonthAndChecksExpectedRevision() {
        PlanSaveResult first = saveService.save(memberId, valid().build(), null);
        jdbc.sql("UPDATE plan_version SET calc_start_month = DATE '2026-01-01' WHERE member_id = ?")
                .param(memberId).update();

        PlanSaveResult second = saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(),
                first.revision());
        PlanView view = saveService.findCurrent(memberId).orElseThrow();

        assertThat(second.revision()).isEqualTo(2L);
        assertThat(view.calcStartMonth()).isEqualTo("2026-01");
        assertThat(view.monthlySavingAmount()).isEqualTo(1_500_000L);
    }

    @Test
    void saveChecksExpectedRevision() {
        PlanSaveResult first = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> saveService.save(memberId, valid().build(), first.revision() - 1))
                .isInstanceOf(PlanConflictException.class)
                .extracting(e -> ((PlanConflictException)e).code())
                .isEqualTo(PlanConflictException.PLAN_CHANGED);
        assertThatThrownBy(() -> saveService.save(memberId, valid().build(), null))
                .isInstanceOf(PlanConflictException.class)
                .extracting(e -> ((PlanConflictException)e).code())
                .isEqualTo(PlanConflictException.PLAN_CHANGED);
    }

    @Test
    void saveStoresNoExistingContributionAndNoRatio() {
        saveService.save(memberId, valid().build(), null);
        PlanView view = saveService.findCurrent(memberId).orElseThrow();

        assertThat(view.savingsRatioPct()).isNull();
        assertThat(jdbc.sql("SELECT existing_monthly_contribution FROM plan_version WHERE member_id = ?")
                .param(memberId).query(Long.class).optional()).isEmpty();
    }

    @Test
    void secondSaveCopiesSavingsRatioFromCurrentVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanSaveResult applied = applyService.apply(memberId, ChangePath.ALTERNATIVE,
                new ConditionOverride(null, null, 70, null), saved.revision());
        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(70);

        saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(), applied.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(70);
    }

    @Test
    void saveWithErrorsThrowsValidationExceptionAndWritesNothing() {
        assertThatThrownBy(() -> saveService.save(memberId, valid().goalAmount(null).build(), null))
                .isInstanceOf(PlanValidationException.class)
                .extracting(e -> ((PlanValidationException)e).errors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(FieldError.required("goalAmount"));
        assertThat(jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single()).isZero();
    }

    @Test
    void applyCreatesVersionFromCurrentPlusChanges() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        PlanSaveResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());
        PlanView view = saveService.findCurrent(memberId).orElseThrow();

        assertThat(applied.revision()).isEqualTo(2L);
        assertThat(view.monthlySavingAmount()).isEqualTo(1_500_000L);
        assertThat(view.goalAmount()).isEqualTo(50_000_000L);
        assertThat(view.targetPeriodMonths()).isEqualTo(36);
        assertThat(view.canUndo()).isTrue();
        assertThat(jdbc.sql("SELECT source FROM plan_version WHERE member_id = ? ORDER BY id DESC LIMIT 1")
                .param(memberId).query(String.class).single()).isEqualTo("COMPARE");
    }

    @Test
    void applyWithInvalidChangeIsValidationError() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.ALTERNATIVE,
                new ConditionOverride(null, null, null, new java.math.BigDecimal("4.00")), saved.revision()))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void applyWithStaleRevisionThrowsConflictWithCurrentPlan() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        applyService.apply(memberId, ChangePath.CHAT, new ConditionOverride(1_500_000L, null, null, null),
                saved.revision());

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                new ConditionOverride(1_600_000L, null, null, null), saved.revision()))
                .isInstanceOf(PlanConflictException.class)
                .satisfies(e -> assertThat(((PlanConflictException)e).current().orElseThrow().monthlySavingAmount())
                        .isEqualTo(1_500_000L));
    }

    @Test
    void undoCopiesPreviousVersionAsNewVersionAndClearsPrevious() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanSaveResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());

        long undoneRevision = planService.undo(memberId, applied.revision());
        PlanView undone = saveService.findCurrent(memberId).orElseThrow();

        assertThat(undoneRevision).isEqualTo(3L);
        assertThat(undone.currentVersionId()).isGreaterThan(applied.planVersionId());
        assertThat(undone.monthlySavingAmount()).isEqualTo(1_200_000L);
        assertThat(undone.revision()).isEqualTo(3L);
        assertThat(undone.canUndo()).isFalse();
    }

    @Test
    void undoWithoutPlanIsNoPlan() {
        assertThatThrownBy(() -> planService.undo(memberId, 1L))
                .isInstanceOf(PlanConflictException.class)
                .extracting(e -> ((PlanConflictException)e).code())
                .isEqualTo(PlanConflictException.NO_PLAN);
    }

    @Test
    void secondUndoIsNothingToUndo() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanSaveResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());
        long undoneRevision = planService.undo(memberId, applied.revision());

        assertThatThrownBy(() -> planService.undo(memberId, undoneRevision))
                .isInstanceOf(PlanConflictException.class)
                .extracting(e -> ((PlanConflictException)e).code())
                .isEqualTo(PlanConflictException.NOTHING_TO_UNDO);
    }

    @Test
    void undoWithStaleRevisionIsPlanChanged() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanSaveResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());

        assertThatThrownBy(() -> planService.undo(memberId, applied.revision() - 1))
                .isInstanceOf(PlanConflictException.class)
                .extracting(e -> ((PlanConflictException)e).code())
                .isEqualTo(PlanConflictException.PLAN_CHANGED);
    }

    @Test
    void applyWithoutPlanIsNoPlan() {
        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), 1L))
                .isInstanceOf(PlanConflictException.class)
                .satisfies(e -> {
                    PlanConflictException conflict = (PlanConflictException)e;
                    assertThat(conflict.code()).isEqualTo(PlanConflictException.NO_PLAN);
                    assertThat(conflict.current()).isEmpty();
                });
    }

    @Test
    void eachChangePublishesPlanChanged(AssertablePublishedEvents events) {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanSaveResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                new ConditionOverride(1_500_000L, null, null, null), saved.revision());
        planService.undo(memberId, applied.revision());

        List<PlanChanged> changes = StreamSupport.stream(events.ofType(PlanChanged.class).spliterator(), false)
                .toList();
        assertThat(changes).extracting(PlanChanged::action).containsExactly("INPUT", "APPLY", "UNDO");
        PlanChanged apply = changes.get(1);
        PlanChanged undo = changes.get(2);
        assertThat(undo.beforeVersionId()).isEqualTo(apply.afterVersionId());
        assertThat(undo.afterVersionId()).isGreaterThan(apply.afterVersionId());
    }

    @Test
    void otherMembersPlanIsInvisible() {
        saveService.save(memberId, valid().build(), null);

        assertThat(saveService.findCurrent(otherMemberId)).isEmpty();
    }
}
