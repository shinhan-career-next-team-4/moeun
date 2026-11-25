package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanApplyService;
import com.moeun.backend.plan.PlanSaveResult;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** F-5 대화 적용 {@code POST /api/chat/messages/{messageId}/apply}(#45, 계약 C12b-7·C12b-7-1). */
class ChatApplyApiTest extends ChatComparisonTestSupport {

    private static final String MONTHLY_500K = "{\"monthlySavingAmount\":500000}";

    @Autowired
    PlanApplyService planApplyService;

    private ConditionOverride monthly(long amount) {
        return new ConditionOverride(amount, null, null, null, null, null);
    }

    private ConditionOverride goal(long amount) {
        return new ConditionOverride(null, null, null, null, amount, null);
    }

    private ResultActions apply(Cookie who, Object messageId, String body) throws Exception {
        return mockMvc.perform(post("/api/chat/messages/{id}/apply", messageId).cookie(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions apply(Cookie who, Object messageId, long expectedRevision) throws Exception {
        return apply(who, messageId, "{\"expectedRevision\":" + expectedRevision + "}");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private long revision(long member) {
        return jdbc.sql("SELECT revision FROM plan WHERE member_id = ?").param(member).query(Long.class).single();
    }

    private long currentVersionId(long member) {
        return jdbc.sql("SELECT current_version_id FROM plan WHERE member_id = ?").param(member).query(Long.class)
                .single();
    }

    private long versionCount(long member) {
        return jdbc.sql("SELECT count(*) FROM plan_version WHERE member_id = ?").param(member).query(Long.class)
                .single();
    }

    /** 적용이 계획을 바꾸지 않았다: 버전 수·현재 버전·revision이 그대로다. */
    private void assertPlanUnchanged(long member, PlanSaveResult saved) {
        assertThat(versionCount(member)).isEqualTo(1);
        assertThat(currentVersionId(member)).isEqualTo(saved.planVersionId());
        assertThat(revision(member)).isEqualTo(saved.revision());
    }

    /** 실제 비교(calc_run)를 남기되 메시지에는 다른 변경값을 저장한다(범위를 벗어난 저장값 재현). */
    private long recalcStoring(long member, ConditionOverride override, String storedChanges) {
        long calcRunId = compareConditions.compare(member, override).calcRunId();
        return insertRecalcResult(member, calcRunId, "COMPLETED", storedChanges);
    }

    // ---------- 성공 ----------

    @Test
    @DisplayName("가장 최근 비교를 적용하면 200 {revision}이고 새 plan_version(source=CHAT)이 생기며 시작월은 그대로다")
    void appliesLatestComparison() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        var startMonth = jdbc.sql("SELECT calc_start_month FROM plan_version WHERE id = ?")
                .param(saved.planVersionId()).query(LocalDate.class).single();

        JsonNode response = body(apply(cookie, recalc.messageId(), saved.revision()).andExpect(status().isOk())
                .andReturn());

        assertThat(response.get("revision").asLong()).isEqualTo(saved.revision() + 1);
        assertThat(revision(memberId)).isEqualTo(saved.revision() + 1);
        assertThat(versionCount(memberId)).isEqualTo(2);
        Map<String, Object> created = jdbc.sql("SELECT source, monthly_saving_amount, calc_start_month "
                        + "FROM plan_version WHERE id = ?").param(currentVersionId(memberId)).query().singleRow();
        assertThat(created.get("source")).isEqualTo("CHAT");
        assertThat(((Number) created.get("monthly_saving_amount")).longValue()).isEqualTo(500_000L);
        assertThat(created.get("calc_start_month")).hasToString(startMonth.toString());
    }

    @Test
    @DisplayName("응답 본문은 revision 하나뿐이다(planVersionId·previousVersionId·undoAvailable·calcRunId 없음)")
    void responseHasOnlyRevision() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        JsonNode response = body(apply(cookie, recalc.messageId(), saved.revision()).andExpect(status().isOk())
                .andReturn());

        Set<String> keys = new HashSet<>();
        response.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("revision");
    }

    @Test
    @DisplayName("저장된 goalAmount 변경을 그대로 넘겨 새 plan_version에 목표 금액이 반영된다(D-090·D-092)")
    void appliesStoredGoalAmount() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, goal(25_000_000L), "{\"goalAmount\":25000000}");

        apply(cookie, recalc.messageId(), saved.revision()).andExpect(status().isOk());

        long goalAmount = jdbc.sql("SELECT goal_amount FROM plan_version WHERE id = ?")
                .param(currentVersionId(memberId)).query(Long.class).single();
        assertThat(goalAmount).isEqualTo(25_000_000L);
    }

    // ---------- 최신 여부(검사 3)와 기준 버전(plan 검사 9) ----------

    @Test
    @DisplayName("최신이 아닌 비교를 적용하면 409 STALE_COMPARISON이고 계획은 바뀌지 않는다")
    void olderComparisonIsStale() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc first = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        recalc(memberId, monthly(400_000L), "{\"monthlySavingAmount\":400000}");

        apply(cookie, first.messageId(), saved.revision()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_COMPARISON"));

        assertPlanUnchanged(memberId, saved);
    }

    @Test
    @DisplayName("최신이 아니면서 revision도 다르면 검사 3이 먼저 걸려 STALE_COMPARISON이다")
    void staleBeatsPlanChanged() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc first = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        recalc(memberId, monthly(400_000L), "{\"monthlySavingAmount\":400000}");

        apply(cookie, first.messageId(), saved.revision() + 7).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_COMPARISON"));

        assertPlanUnchanged(memberId, saved);
    }

    @Test
    @DisplayName("비교 뒤 입력을 수정해 저장하면 기준 버전이 달라 409 STALE_COMPARISON이다(검사 9)")
    void inputEditAfterComparisonIsStale() throws Exception {
        PlanSaveResult v1 = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        PlanSaveResult v2 = savePlan(memberId, v1.revision(), 350_000L);

        apply(cookie, recalc.messageId(), v2.revision()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_COMPARISON"));

        assertThat(versionCount(memberId)).isEqualTo(2);
        assertThat(currentVersionId(memberId)).isEqualTo(v2.planVersionId());
        assertThat(revision(memberId)).isEqualTo(v2.revision());
    }

    @Test
    @DisplayName("비교(V1) → 적용(V2) → 되돌리기(복사본 V3) 뒤 적용하면 409 STALE_COMPARISON이다(D-068)")
    void undoCopyIsStale() throws Exception {
        PlanSaveResult v1 = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        var applied = planApplyService.apply(memberId, ChangePath.CHAT,
                Map.of("monthlySavingAmount", JsonNodeFactory.instance.numberNode(500_000)), v1.revision(),
                v1.planVersionId());
        awaitEventsCompleted();
        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + applied.revision() + "}"))
                .andExpect(status().isOk());
        awaitEventsCompleted();
        long versionsBefore = versionCount(memberId);
        long revisionBefore = revision(memberId);

        apply(cookie, recalc.messageId(), revisionBefore).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_COMPARISON"));

        assertThat(versionCount(memberId)).isEqualTo(versionsBefore);
        assertThat(revision(memberId)).isEqualTo(revisionBefore);
    }

    // ---------- revision·동시성(plan 검사 8) ----------

    @Test
    @DisplayName("revision이 다르면 409 PLAN_CHANGED이고 계획은 바뀌지 않는다")
    void revisionMismatch() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        apply(cookie, recalc.messageId(), saved.revision() + 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_CHANGED"));

        assertPlanUnchanged(memberId, saved);
    }

    @Test
    @DisplayName("같은 메시지를 거의 동시에 두 번 적용하면 하나만 200이고 다른 하나는 409 PLAN_CHANGED이며 버전은 하나만 늘어난다")
    void concurrentAppliesCreateOneVersion() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return apply(cookie, recalc.messageId(), saved.revision()).andReturn();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 409) {
                    assertThat(body(result).get("code").asString()).isEqualTo("PLAN_CHANGED");
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(versionCount(memberId)).isEqualTo(2);
        assertThat(revision(memberId)).isEqualTo(saved.revision() + 1);
    }

    // ---------- 계획 없음(plan 검사 5) ----------

    @Test
    @DisplayName("저장된 계획이 없는 회원이 적용하면 409 NO_PLAN이다")
    void noPlan() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        jdbc.sql("DELETE FROM plan WHERE member_id = ?").param(memberId).update();

        apply(cookie, recalc.messageId(), 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_PLAN"));
    }

    // ---------- 저장값 검증(plan 검사 6·7) ----------

    @Test
    @DisplayName("저장된 월 저축액이 범위를 벗어나면 422 VALIDATION_FAILED이고 errors[]에 min·max·unit만 있다(minDisplay·maxDisplay 없음)")
    void outOfRangeStoredValue() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcStoring(memberId, monthly(500_000L), "{\"monthlySavingAmount\":99999999}");

        JsonNode response = body(apply(cookie, messageId, saved.revision()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn());

        JsonNode error = response.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo("monthlySavingAmount");
        assertThat(error.get("reason").asString()).isEqualTo("OUT_OF_RANGE");
        assertThat(error.get("min").asLong()).isEqualTo(10_000L);
        assertThat(error.get("max").asLong()).isEqualTo(10_000_000L);
        assertThat(error.get("unit").asString()).isEqualTo("KRW");
        assertThat(error.has("minDisplay")).isFalse();
        assertThat(error.has("maxDisplay")).isFalse();
        assertPlanUnchanged(memberId, saved);
    }

    @Test
    @DisplayName("현재 규칙을 위반하는 저장된 목표 금액은 422 VALIDATION_FAILED이고 계획은 바뀌지 않는다(D-090·D-092)")
    void goalAmountBelowMinimum() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcStoring(memberId, goal(25_000_000L), "{\"goalAmount\":9999}");

        JsonNode response = body(apply(cookie, messageId, saved.revision()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn());

        assertThat(response.get("errors").get(0).get("field").asString()).isEqualTo("goalAmount");
        assertPlanUnchanged(memberId, saved);
    }

    @Test
    @DisplayName("저장된 값이 정수·소수 자릿수를 위반하면 422 VALIDATION_FAILED(NOT_INTEGER·TOO_MANY_DECIMALS, scale)다")
    void integerAndDecimalViolations() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long notInteger = recalcStoring(memberId, monthly(500_000L), "{\"monthlySavingAmount\":500000.5}");
        JsonNode first = body(apply(cookie, notInteger, saved.revision()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn());
        long tooManyDecimals = recalcStoring(memberId, new ConditionOverride(null, null, null,
                new BigDecimal("3.5"), null, null), "{\"savingsRateInputPct\":3.123}");

        JsonNode second = body(apply(cookie, tooManyDecimals, saved.revision())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn());

        assertThat(first.get("errors").get(0).get("reason").asString()).isEqualTo("NOT_INTEGER");
        assertThat(second.get("errors").get(0).get("reason").asString()).isEqualTo("TOO_MANY_DECIMALS");
        assertThat(second.get("errors").get(0).get("scale").asInt()).isEqualTo(2);
        assertPlanUnchanged(memberId, saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"heldAssets\":1000000}", "{\"monthly_saving_amount\":500000}"})
    @DisplayName("허용 밖 키(heldAssets, snake_case 키)가 저장돼 있으면 422 FIELD_NOT_CHANGEABLE이다(방어선, 키를 바꿔 넘기지 않는다)")
    void notChangeableKey(String storedChanges) throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcStoring(memberId, monthly(500_000L), storedChanges);

        apply(cookie, messageId, saved.revision()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"));

        assertPlanUnchanged(memberId, saved);
    }

    // ---------- 메시지 조회(검사 2)와 형식(검사 1) ----------

    @Test
    @DisplayName("없는 id·남의 메시지·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지는 모두 같은 404 NOT_FOUND이고 계획은 바뀌지 않는다")
    void notFoundVariants() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        savePlan(otherMemberId, null, 300_000L);
        Recalc mine = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        Recalc theirs = recalc(otherMemberId, monthly(500_000L), MONTHLY_500K);
        long explanation = insertWithRun(memberId, "EXPLANATION", "COMPLETED", mine.calcRunId(), null);
        long pending = insertRecalcResult(memberId, mine.calcRunId(), "PENDING", MONTHLY_500K);
        long failed = insertRecalcResult(memberId, mine.calcRunId(), "FAILED", MONTHLY_500K);
        List<Long> ids = List.of(999_999_999L, theirs.messageId(), explanation, pending, failed);
        List<String> bodies = new ArrayList<>();

        for (long id : ids) {
            MvcResult result = apply(cookie, id, saved.revision()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND")).andReturn();
            bodies.add(withoutInstance(body(result)));
        }

        assertThat(bodies).allMatch(bodies.get(0)::equals);
        assertPlanUnchanged(memberId, saved);
        assertPlanUnchanged(otherMemberId, new PlanSaveResult(currentVersionId(otherMemberId),
                revision(otherMemberId)));
    }

    /** instance는 요청 경로(id 포함)라 구분 대상이 아니다. 나머지(status·title·code·errors)가 같아야 id를 추측할 수 없다. */
    private String withoutInstance(JsonNode problem) {
        return problem.get("status") + "|" + problem.get("title") + "|" + problem.get("code") + "|"
                + problem.get("errors");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "[]", "{}", "{\"expectedRevision\":null}",
        "{\"expectedRevision\":\"3\"}", "{\"expectedRevision\":3.5}", "{\"expectedRevision\":true}",
        "{\"expectedRevision\":99999999999999999999}"})
    @DisplayName("파싱할 수 없는 본문이거나 expectedRevision이 정수가 아니면 400 MALFORMED_REQUEST다(메시지가 없어도 먼저 걸린다)")
    void malformedBody(String requestBody) throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        apply(cookie, recalc.messageId(), requestBody).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        apply(cookie, 999_999_999L, requestBody).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertPlanUnchanged(memberId, saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    @DisplayName("messageId가 정수가 아니면 400 MALFORMED_REQUEST다")
    void malformedMessageId(String messageId) throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);

        apply(cookie, messageId, saved.revision()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertPlanUnchanged(memberId, saved);
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/chat/messages/{id}/apply", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isUnauthorized());
    }
}
