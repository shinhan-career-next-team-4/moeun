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
@Import({TestcontainersConfig.class, LoadedEtfProductsConfig.class})
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
    void saveStoresRequestedSavingsRatio() {
        saveService.save(memberId, valid().savingsRatioPct(83).build(), null);

        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(83);
    }

    @Test
    void saveWithNullSavingsRatioClearsAppliedRatio() {
        // D-086: 토글을 끄고 저장하면(null) 대화·대안으로 적용했던 비중이 기본 비중으로 돌아간다. 복사하지 않는다
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.ALTERNATIVE,
                changes("savingsRatioPct", 70L), saved.revision(), null);
        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(70);

        saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(), applied.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isNull();
    }

    @Test
    void saveKeepsRatioWhenRequestSendsItBack() {
        // (h) 토글 ON·칸 비움: 화면이 가장 최근 응답의 비중을 그대로 되보내 현재 비중이 유지된다
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.ALTERNATIVE,
                changes("savingsRatioPct", 70L), saved.revision(), null);

        saveService.save(memberId, valid().savingsRatioPct(70).build(), applied.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().savingsRatioPct()).isEqualTo(70);
    }

    @Test
    void firstSaveStoresRepresentativeEtfProduct() {
        saveService.save(memberId, valid().build(), null);

        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("SP500");
        assertThat(jdbc.sql("SELECT etf_product_code FROM plan_version WHERE member_id = ?").param(memberId)
                .query(String.class).single()).isEqualTo("SP500");
    }

    @Test
    void secondSaveKeepsEtfProductOfCurrentVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        setEtfProduct(saved.planVersionId(), "069500");

        saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(), saved.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("069500");
    }

    @Test
    void applyKeepsEtfProductOfCurrentVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        setEtfProduct(saved.planVersionId(), "133690");

        applyService.apply(memberId, ChangePath.COMPARE, changes("monthlySavingAmount", 1_500_000L),
                saved.revision(), null);

        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("133690");
    }

    @Test
    void undoRestoresEtfProductOfPreviousVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        setEtfProduct(saved.planVersionId(), "069500");
        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE,
                changes("monthlySavingAmount", 1_500_000L), saved.revision(), null);
        setEtfProduct(applied.planVersionId(), "133690");

        undoService.undo(memberId, applied.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("069500");
    }

    @Test
    void findVersionReturnsEtfProductOfThatVersion() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        setEtfProduct(saved.planVersionId(), "069500");
        applyService.apply(memberId, ChangePath.COMPARE, changes("monthlySavingAmount", 1_500_000L),
                saved.revision(), null);

        assertThat(saveService.findVersion(memberId, saved.planVersionId()).orElseThrow().etfProductCode())
                .isEqualTo("069500");
    }

    @Test
    void applyChangesEtfProductAndUndoRestoresIt() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE, productChange("069500"),
                saved.revision(), null);
        PlanView view = saveService.findCurrent(memberId).orElseThrow();

        assertThat(view.etfProductCode()).isEqualTo("069500");
        assertThat(view.monthlySavingAmount()).isEqualTo(1_200_000L);   // 다른 조건은 그대로
        assertThat(applied.revision()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT source FROM plan_version WHERE id = ?").param(applied.planVersionId())
                .query(String.class).single()).isEqualTo("COMPARE");

        undoService.undo(memberId, applied.revision());

        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("SP500");
    }

    @Test
    void applyChangingEtfProductPublishesPlanChanged(AssertablePublishedEvents events) {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        PlanApplyResult applied = applyService.apply(memberId, ChangePath.COMPARE, productChange("133690"),
                saved.revision(), null);

        assertThat(events.ofType(PlanChanged.class)
                .matching(event -> event.afterVersionId() == applied.planVersionId()
                        && "APPLY".equals(event.action())))
                .hasSize(1);
    }

    @Test
    void applyWithEtfProductWithoutDataIsValidationErrorAndWritesNothing() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        long versions = jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single();

        assertThatThrownBy(() -> applyService.apply(memberId, ChangePath.COMPARE, productChange("999999"),
                saved.revision(), null))
                .isInstanceOf(ConditionViolationException.class)
                .extracting(e -> ((ConditionViolationException)e).errors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(FieldError.notAvailable("etfProductCode"));
        assertThat(jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single()).isEqualTo(versions);
        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("SP500");
    }

    @Test
    void applyChangesEtfProductWithOtherCondition() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        Map<String, JsonNode> changes = new java.util.LinkedHashMap<>(productChange("133690"));
        changes.put("monthlySavingAmount", JsonNodeFactory.instance.numberNode(1_500_000L));

        applyService.apply(memberId, ChangePath.COMPARE, changes, saved.revision(), null);
        PlanView view = saveService.findCurrent(memberId).orElseThrow();

        assertThat(view.etfProductCode()).isEqualTo("133690");
        assertThat(view.monthlySavingAmount()).isEqualTo(1_500_000L);
    }

    @Test
    void chatAppliesEtfProduct() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        applyService.apply(memberId, ChangePath.CHAT, productChange("069500"), saved.revision(), null);

        assertThat(saveService.findCurrent(memberId).orElseThrow().etfProductCode()).isEqualTo("069500");
        assertThat(jdbc.sql("SELECT count(*) FROM plan_version").query(Long.class).single()).isEqualTo(2L);
    }

    private static Map<String, JsonNode> productChange(String etfProductCode) {
        return Map.of("etfProductCode", JsonNodeFactory.instance.stringNode(etfProductCode));
    }

    /**
     * 재저장·적용이 현재 종목을 이어받는지 보려고 준비 단계에서만 행을 직접 고친다.
     * 앱 코드는 plan_version을 INSERT만 한다(ADR-002).
     */
    private void setEtfProduct(long planVersionId, String etfProductCode) {
        jdbc.sql("UPDATE plan_version SET etf_product_code = ? WHERE id = ?")
                .param(etfProductCode).param(planVersionId).update();
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
                changes("heldAssets", 10_000_000L), saved.revision(), null))
                .isInstanceOf(ConditionViolationException.class)
                .satisfies(e -> assertThat(((ConditionViolationException)e).code())
                        .isEqualTo("FIELD_NOT_CHANGEABLE"));
        assertThat(versionCount()).isOne();
    }

    @Test
    void applyWithGoalAmountUpdatesPlanGoalAmount() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        PlanApplyResult applied = applyService.apply(memberId, ChangePath.CHAT,
                changes("goalAmount", 80_000_000L), saved.revision(), null);

        assertThat(applied.revision()).isEqualTo(2L);
        assertThat(saveService.findCurrent(memberId).orElseThrow().goalAmount()).isEqualTo(80_000_000L);
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
