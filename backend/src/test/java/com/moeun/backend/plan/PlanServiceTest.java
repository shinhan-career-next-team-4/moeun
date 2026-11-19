package com.moeun.backend.plan;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.AssertablePublishedEvents;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.application.PlanService;
import com.moeun.backend.plan.application.PlanUndoService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

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
    PlanUndoService undoService;

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
                .isInstanceOf(PlanChangedException.class);
        assertThatThrownBy(() -> saveService.save(memberId, valid().build(), null))
                .isInstanceOf(PlanChangedException.class);
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
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.ALTERNATIVE,
                changes("savingsRatioPct", 70L), saved.revision(), null);
        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(70);

        saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(), applied.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(70);
    }

    @Test
    void saveWithErrorsThrowsValidationExceptionAndWritesNothing() {
        assertThatThrownBy(() -> saveService.save(memberId, valid().goalAmount(null).build(), null))
                .isInstanceOf(ConditionViolationException.class)
                .extracting(e -> ((ConditionViolationException)e).errors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(FieldError.required("goalAmount"));
        assertThat(jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single()).isZero();
    }

    @Test
    void applyCreatesVersionFromCurrentPlusChanges() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);
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
                changes("savingsRateInputPct", new BigDecimal("4.00")), saved.revision(), null))
                .isInstanceOf(ConditionViolationException.class);
    }

    @Test
    void applyWithStaleRevisionThrowsPlanChangedAndKeepsCurrent() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        applyService.apply(memberId, ChangePath.CHAT, changes("monthlySavingAmount", 1_500_000L),
                saved.revision(), null);

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                changes("monthlySavingAmount", 1_600_000L), saved.revision(), null))
                .isInstanceOf(PlanChangedException.class);
        assertThat(saveService.findCurrent(memberId).orElseThrow().monthlySavingAmount()).isEqualTo(1_500_000L);
    }

    @Test
    void undoCopiesPreviousVersionAsNewVersionAndClearsPrevious() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);

        long undoneRevision = undoService.undo(memberId, applied.revision());
        PlanView undone = saveService.findCurrent(memberId).orElseThrow();

        assertThat(undoneRevision).isEqualTo(3L);
        assertThat(undone.currentVersionId()).isGreaterThan(applied.planVersionId());
        assertThat(undone.monthlySavingAmount()).isEqualTo(1_200_000L);
        assertThat(undone.revision()).isEqualTo(3L);
        assertThat(undone.canUndo()).isFalse();
    }

    @Test
    void undoWithoutPlanIsNoPlan() {
        assertThatThrownBy(() -> undoService.undo(memberId, 1L))
                .isInstanceOf(NoPlanException.class);
    }

    @Test
    void secondUndoIsNothingToUndo() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);
        long undoneRevision = undoService.undo(memberId, applied.revision());

        assertThatThrownBy(() -> undoService.undo(memberId, undoneRevision))
                .isInstanceOf(NothingToUndoException.class);
    }

    @Test
    void undoWithStaleRevisionIsPlanChanged() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);

        assertThatThrownBy(() -> undoService.undo(memberId, applied.revision() - 1))
                .isInstanceOf(PlanChangedException.class);
    }

    @Test
    void applyWithoutPlanIsNoPlan() {
        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), 1L, null))
                .isInstanceOf(NoPlanException.class);
    }

    @Test
    void eachChangePublishesPlanChanged(AssertablePublishedEvents events) {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);
        undoService.undo(memberId, applied.revision());

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

    @Test
    void findVersionReturnsAnOlderVersionOfMyPlan() {
        PlanSaveResult first = saveService.save(memberId, valid().build(), null);
        long originalMonthly = saveService.findCurrent(memberId).orElseThrow().monthlySavingAmount();
        applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), first.revision(), null);

        PlanView old = saveService.findVersion(memberId, first.planVersionId()).orElseThrow();

        assertThat(old.currentVersionId()).isEqualTo(first.planVersionId());
        assertThat(old.monthlySavingAmount()).isEqualTo(originalMonthly);
        assertThat(saveService.findCurrent(memberId).orElseThrow().monthlySavingAmount()).isEqualTo(1_500_000L);
    }

    @Test
    void findVersionHidesOtherMembersVersionAndUnknownId() {
        PlanSaveResult mine = saveService.save(memberId, valid().build(), null);

        assertThat(saveService.findVersion(otherMemberId, mine.planVersionId())).isEmpty();
        assertThat(saveService.findVersion(memberId, mine.planVersionId() + 9_999L)).isEmpty();
    }

    @Test
    void applyReturnsPreviousVersionIdAndKeepsCalcStartMonth() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        String startMonth = saveService.findCurrent(memberId).orElseThrow().calcStartMonth();

        PlanApplyResult applied = applyService.apply(memberId, ChangePath.CHAT,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), saved.planVersionId());

        assertThat(applied.previousVersionId()).isEqualTo(saved.planVersionId());
        assertThat(applied.planVersionId()).isGreaterThan(saved.planVersionId());
        assertThat(applied.revision()).isEqualTo(2L);
        assertThat(saveService.findCurrent(memberId).orElseThrow().calcStartMonth()).isEqualTo(startMonth);
    }

    @Test
    void applyRejectsInputEditPath() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.INPUT_EDIT,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyWithNotChangeableKeyIsFieldNotChangeable() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                changes("goalAmount", 10_000_000L), saved.revision(), null))
                .isInstanceOf(ConditionViolationException.class)
                .satisfies(e -> assertThat(((ConditionViolationException)e).code())
                        .isEqualTo("FIELD_NOT_CHANGEABLE"));
        assertThat(versionCount()).isOne();
    }

    @Test
    void applyOutOfRangeChangeCarriesRangeInErrors() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                changes("monthlySavingAmount", 1L), saved.revision(), null))
                .isInstanceOf(ConditionViolationException.class)
                .satisfies(e -> {
                    ConditionViolationException violation = (ConditionViolationException)e;
                    assertThat(violation.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(violation.errors()).singleElement().satisfies(error -> {
                        assertThat(error.field()).isEqualTo("monthlySavingAmount");
                        assertThat(error.reason()).isEqualTo("OUT_OF_RANGE");
                        assertThat(error.min()).isNotNull();
                        assertThat(error.max()).isNotNull();
                        assertThat(error.unit()).isNotNull();
                        assertThat(error.scale()).isNull();
                    });
                });
    }

    @Test
    void applyWithStaleBaseVersionIsStaleComparison() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);
        long versionsBefore = versionCount();

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                changes("monthlySavingAmount", 1_600_000L), applied.revision(), saved.planVersionId()))
                .isInstanceOf(StaleBaseVersionException.class);
        assertThat(versionCount()).isEqualTo(versionsBefore);
    }

    @Test
    void staleRevisionWinsOverStaleBaseVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                changes("monthlySavingAmount", 1_600_000L), saved.revision(), saved.planVersionId()))
                .isInstanceOf(PlanChangedException.class);
    }

    @Test
    void undoMakesCopyThatStalesComparisonsOnTheOldVersion() {
        PlanSaveResult first = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), first.revision(), first.planVersionId());

        long undoneRevision = undoService.undo(memberId, applied.revision());
        PlanView undone = saveService.findCurrent(memberId).orElseThrow();

        assertThat(undone.currentVersionId()).isNotEqualTo(first.planVersionId());
        assertThat(undone.canUndo()).isFalse();
        assertThat(undone.revision()).isEqualTo(undoneRevision);
        assertThat(jdbc.sql("SELECT source FROM plan_version WHERE id = ?")
                .param(undone.currentVersionId()).query(String.class).single()).isEqualTo("INPUT");
        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.CHAT,
                changes("monthlySavingAmount", 1_600_000L), undoneRevision, first.planVersionId()))
                .isInstanceOf(StaleBaseVersionException.class);
    }

    @Test
    void undoWithStaleRevisionWritesNoVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);
        long versionsBefore = versionCount();

        assertThatThrownBy(() -> undoService.undo(memberId, applied.revision() - 1))
                .isInstanceOf(PlanChangedException.class);
        assertThat(versionCount()).isEqualTo(versionsBefore);
    }

    private long versionCount() {
        return jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single();
    }

    private static Map<String, JsonNode> changes(String key, long value) {
        return Map.of(key, JsonNodeFactory.instance.numberNode(value));
    }

    private static Map<String, JsonNode> changes(String key, BigDecimal value) {
        return Map.of(key, JsonNodeFactory.instance.numberNode(value));
    }
}
