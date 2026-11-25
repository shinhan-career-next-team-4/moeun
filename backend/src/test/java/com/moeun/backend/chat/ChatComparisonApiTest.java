package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanApplyService;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** F-4 비교 결과 조회 {@code GET /api/chat/messages/{id}/comparison}(#44, 계약 C12b-6). */
class ChatComparisonApiTest extends ChatComparisonTestSupport {

    private static final Set<String> CONDITION_KEYS = Set.of("goalAmount", "monthlySavingAmount", "savingsRatioPct",
            "savingsRateInputPct", "targetPeriodMonths");
    private static final String MONTHLY_500K = "{\"monthlySavingAmount\":500000}";

    @Autowired
    PlanApplyService planApplyService;

    @Autowired
    ReferenceDataLoader loader;

    private ConditionOverride monthly(long amount) {
        return new ConditionOverride(amount, null, null, null, null, null);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode okBody(Object messageId) throws Exception {
        return body(comparison(cookie, messageId).andExpect(status().isOk()).andReturn());
    }

    /** 응답과 같은 글자 경로로 만든 기대 트리. valueToTree는 BigDecimal 자릿수(2.840)를 남겨 파싱한 응답(2.84)과 달라진다. */
    private JsonNode tree(Object value) {
        return json.readTree(json.writeValueAsString(value));
    }

    private Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    // ---------- 조회와 applicable ----------

    @Test
    @DisplayName("가장 최근 RECALC_RESULT를 조회하면 current·changed·diff와 applicable=true가 온다")
    void latestRecalcResultIsApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        JsonNode response = okBody(recalc.messageId());

        assertThat(response.get("messageId").asLong()).isEqualTo(recalc.messageId());
        assertThat(response.get("applicable").asBoolean()).isTrue();
        assertThat(response.get("current").isObject()).isTrue();
        assertThat(response.get("changed").isObject()).isTrue();
        assertThat(response.get("diff").isObject()).isTrue();
    }

    @Test
    @DisplayName("비교 결과가 두 개일 때 첫 번째를 조회하면 applicable=false, 두 번째는 true다(D-041)")
    void onlyLatestOfTwoIsApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc first = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        Recalc second = recalc(memberId, monthly(400_000L), "{\"monthlySavingAmount\":400000}");

        assertThat(okBody(first.messageId()).get("applicable").asBoolean()).isFalse();
        assertThat(okBody(second.messageId()).get("applicable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("더 나중의 PENDING·FAILED 비교, 남의 비교, 다른 종류의 메시지는 최신 판정에 끼지 않는다")
    void onlyCompletedRecalcResultsOfTheMemberCount() throws Exception {
        savePlan(memberId, null, 300_000L);
        savePlan(otherMemberId, null, 300_000L);
        Recalc mine = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        Recalc theirs = recalc(otherMemberId, monthly(500_000L), MONTHLY_500K);
        insertRecalcResult(memberId, mine.calcRunId(), "PENDING", MONTHLY_500K);
        insertRecalcResult(memberId, mine.calcRunId(), "FAILED", MONTHLY_500K);
        insertWithRun(memberId, "EXPLANATION", "COMPLETED", mine.calcRunId(), null);

        assertThat(okBody(mine.messageId()).get("applicable").asBoolean()).isTrue();
        assertThat(body(comparison(otherCookie, theirs.messageId()).andExpect(status().isOk()).andReturn())
                .get("applicable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("그 사이 계획이 바뀌면 applicable=false다")
    void planChangedMeansNotApplicable() throws Exception {
        PlanSaveResult first = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        savePlan(memberId, first.revision(), 350_000L);

        assertThat(okBody(recalc.messageId()).get("applicable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("비교(V1) → 적용(V2) → 되돌리기(복사본 V3) 뒤 조회하면 기준 V1≠현재 V3이라 applicable=false다(D-068)")
    void undoCopyIsNotTheBaseVersion() throws Exception {
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

        assertThat(okBody(recalc.messageId()).get("applicable").asBoolean()).isFalse();
    }

    // ---------- 계산 값 ----------

    @Test
    @DisplayName("저장된 camelCase 변경값을 그대로 넘겨 /internal/calc 때와 같은 current·changed·diff·display가 나온다")
    void sameComparisonAsInternalCalc() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        JsonNode response = okBody(recalc.messageId());

        var then = recalc.then().comparison();
        assertThat(response.get("current")).isEqualTo(tree(CalcResultView.from(then.current())));
        assertThat(response.get("changed")).isEqualTo(tree(CalcResultView.from(then.changed())));
        assertThat(response.get("diff")).isEqualTo(tree(then.diff()));
        assertThat(response.get("display")).isEqualTo(tree(recalc.then().display()));
    }

    @Test
    @DisplayName("응답 모양: 9개 키(messageId·applicable·changedKeys·accumulatedKeys·conditions·current·changed·"
            + "diff·display)만 있고 "
            + "calcRunId·monthlyPoints는 없다")
    void responseShape() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        JsonNode response = okBody(recalc.messageId());

        assertThat(keys(response)).containsExactlyInAnyOrder("messageId", "applicable", "changedKeys", "conditions",
                "current", "changed", "diff", "display", "accumulatedKeys");
        // 쌓인 조건 없이 계산한 비교는 이번 변경만 쌓인 조건이다(#418)
        assertThat(response.get("accumulatedKeys")).extracting(JsonNode::asString)
                .containsExactly("monthlySavingAmount");
        assertThat(keys(response.get("conditions"))).containsExactlyInAnyOrder("current", "changed");
        assertThat(keys(response.get("conditions").get("current"))).isEqualTo(CONDITION_KEYS);
        assertThat(keys(response.get("conditions").get("changed"))).isEqualTo(CONDITION_KEYS);
        assertThat(response.toString()).doesNotContain("calcRunId").doesNotContain("monthlyPoints");
        assertThat(response.get("display").isObject()).isTrue();
        assertThat(response.get("conditions").get("current").get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(response.get("conditions").get("changed").get("monthlySavingAmount").asLong())
                .isEqualTo(500_000L);
    }

    @Test
    @DisplayName("그 뒤 활성 금리 버전이 바뀌어도 기록의 데이터 버전으로 계산한다")
    void usesRecordedDataVersions() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        var then = recalc.then().comparison();
        long recordedRateVersion = then.current().dataVersions().savingsRateVersionId();
        var newer = loader.load(DataKind.SAVINGS_RATE,
                new VersionSpec("test-chat-later", LocalDate.of(2026, 11, 1), "테스트용 가짜 데이터"),
                new ByteArrayInputStream("base_month,avg_rate_pct\n2026-11,1.00\n".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        try {
            JsonNode response = okBody(recalc.messageId());

            assertThat(response.get("current")).isEqualTo(tree(CalcResultView.from(then.current())));
            assertThat(response.get("changed")).isEqualTo(tree(CalcResultView.from(then.changed())));
        } finally {
            // 같은 컨텍스트를 쓰는 다른 테스트가 원래 활성 금리로 계산하도록 되돌린다
            jdbc.sql("UPDATE data_version SET is_active = false WHERE id = ?").param(newer.id()).update();
            jdbc.sql("UPDATE data_version SET is_active = true WHERE id = ?").param(recordedRateVersion).update();
        }
    }

    @Test
    @DisplayName("ETF 0%(savingsRatioPct=100) 비교는 changed 시나리오가 AVG 하나이고 display도 AVG 키만 있다")
    void etfZeroPercentHasOnlyAverage() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, new ConditionOverride(null, null, 100, null, null, null),
                "{\"savingsRatioPct\":100}");

        JsonNode response = okBody(recalc.messageId());

        assertThat(response.get("changed").get("scenarios")).hasSize(1);
        assertThat(response.get("changed").get("scenarios").get(0).get("scenario").asText()).isEqualTo("AVG");
        assertThat(response.get("display")).isEqualTo(tree(recalc.then().display()));
        response.get("display").propertyNames().forEach(key -> {
            if (key.startsWith("changed.")) {
                assertThat(key.toLowerCase()).doesNotContain("good").doesNotContain("bad");
            }
        });
    }

    // ---------- changedKeys와 조건 ----------

    @Test
    @DisplayName("condition_changes에 monthlySavingAmount만 있으면 changedKeys=[monthlySavingAmount]다")
    void changedKeysOfSingleChange() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);

        JsonNode changedKeys = okBody(recalc.messageId()).get("changedKeys");

        assertThat(changedKeys.isArray()).isTrue();
        assertThat(changedKeys).hasSize(1);
        assertThat(changedKeys.get(0).asText()).isEqualTo("monthlySavingAmount");
    }

    @Test
    @DisplayName("키가 여러 개면 changedKeys는 순서와 무관하게 같은 집합이다")
    void changedKeysOfSeveralChanges() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, new ConditionOverride(500_000L, 24, null, null, null, null),
                "{\"targetPeriodMonths\":24,\"monthlySavingAmount\":500000}");

        Set<String> changedKeys = new HashSet<>();
        okBody(recalc.messageId()).get("changedKeys").forEach(key -> changedKeys.add(key.asText()));

        assertThat(changedKeys).isEqualTo(Set.of("monthlySavingAmount", "targetPeriodMonths"));
    }

    @Test
    @DisplayName("goalAmount를 바꾼 저장값은 그대로 재현하고 changedKeys·conditions에 변경 전·후 목표 금액이 담긴다")
    void goalAmountChange() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, new ConditionOverride(null, null, null, null, 30_000_000L, null),
                "{\"goalAmount\":30000000}");

        JsonNode response = okBody(recalc.messageId());

        Set<String> changedKeys = new HashSet<>();
        response.get("changedKeys").forEach(key -> changedKeys.add(key.asText()));
        assertThat(changedKeys).contains("goalAmount");
        assertThat(response.get("conditions").get("current").get("goalAmount").asLong()).isEqualTo(20_000_000L);
        assertThat(response.get("conditions").get("changed").get("goalAmount").asLong()).isEqualTo(30_000_000L);
        assertThat(response.get("changed")).isEqualTo(tree(
                CalcResultView.from(recalc.then().comparison().changed())));
        assertThat(response.get("display")).isEqualTo(tree(recalc.then().display()));
    }

    @Test
    @DisplayName("저장된 변경값이 지금 rules.yml 범위 밖이어도 422가 아니라 200으로 그 값 그대로 재계산한다")
    void outOfRangeStoredValueIsReproduced() throws Exception {
        savePlan(memberId, null, 300_000L);
        // 월 저축액 상한은 1,000만원이다. 대화 때는 통과했지만 지금은 범위 밖인 값을 흉내 낸다
        Recalc recalc = recalc(memberId, monthly(50_000_000L), "{\"monthlySavingAmount\":50000000}");

        JsonNode response = okBody(recalc.messageId());

        assertThat(response.get("conditions").get("changed").get("monthlySavingAmount").asLong())
                .isEqualTo(50_000_000L);
        assertThat(response.get("changed")).isEqualTo(tree(
                CalcResultView.from(recalc.then().comparison().changed())));
    }

    @Test
    @DisplayName("savingsRateInputPct 소수 값도 그대로 재계산한다")
    void decimalRateIsReproduced() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId,
                new ConditionOverride(null, null, null, new BigDecimal("3.5"), null, null),
                "{\"savingsRateInputPct\":3.5}");

        JsonNode response = okBody(recalc.messageId());

        assertThat(response.get("conditions").get("changed").get("savingsRateInputPct").decimalValue())
                .isEqualByComparingTo("3.5");
    }

    // ---------- 오류 ----------

    @ParameterizedTest
    @ValueSource(strings = {"{\"monthlySavingAmount\":\"abc\"}", "{\"monthlySavingAmount\":500000.5}",
        "{\"interestRate\":3}", "{\"heldAssets\":1000000}", "{\"monthlySavingAmount\":true}",
        "{\"monthlySavingAmount\":[1]}"})
    @DisplayName("타입 변환이 안 되거나 허용 5키 밖의 키가 저장된 RECALC_RESULT는 500 INTERNAL_ERROR다(detail에 내부 메시지 없음)")
    void unconvertibleStoredChangesAreInternalError(String storedChanges) throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        long broken = insertRecalcResult(memberId, recalc.calcRunId(), "COMPLETED", storedChanges);

        MvcResult result = comparison(cookie, broken).andExpect(status().isInternalServerError()).andReturn();

        JsonNode problem = body(result);
        assertThat(problem.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.get("detail").asText()).doesNotContain("monthlySavingAmount").doesNotContain("interestRate")
                .doesNotContain("heldAssets").doesNotContain("Exception");
    }

    @Test
    @DisplayName("없는 id·남의 메시지·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지는 모두 같은 404 NOT_FOUND다")
    void notFoundCasesAreIndistinguishable() throws Exception {
        savePlan(memberId, null, 300_000L);
        savePlan(otherMemberId, null, 300_000L);
        Recalc mine = recalc(memberId, monthly(500_000L), MONTHLY_500K);
        Recalc theirs = recalc(otherMemberId, monthly(500_000L), MONTHLY_500K);
        long explanation = insertWithRun(memberId, "EXPLANATION", "COMPLETED", mine.calcRunId(), null);
        long pending = insertRecalcResult(memberId, mine.calcRunId(), "PENDING", MONTHLY_500K);
        long failed = insertRecalcResult(memberId, mine.calcRunId(), "FAILED", MONTHLY_500K);
        long question = insertQuestion(memberId);

        List<JsonNode> problems = new java.util.ArrayList<>();
        for (long id : new long[] {99_999_999L, theirs.messageId(), explanation, pending, failed, question}) {
            MvcResult result = comparison(cookie, id).andExpect(status().isNotFound()).andReturn();
            problems.add(body(result));
        }

        // instance(요청 경로)만 id마다 다르다. 나머지는 구분되지 않아야 한다
        problems.forEach(problem -> ((tools.jackson.databind.node.ObjectNode) problem).remove("instance"));
        for (JsonNode problem : problems) {
            assertThat(problem.get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(problem).isEqualTo(problems.get(0));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    @DisplayName("messageId가 정수가 아니면 400 MALFORMED_REQUEST다")
    void nonIntegerIdIsMalformed(String id) throws Exception {
        MvcResult result = comparison(cookie, id).andExpect(status().isBadRequest()).andReturn();

        assertThat(body(result).get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    private long insertQuestion(long member) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content) "
                        + "VALUES (?, 'USER', 'QUESTION', 'COMPLETED', 'q') RETURNING id")
                .param(member).query(Long.class).single();
    }
}
