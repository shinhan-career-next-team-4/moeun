package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import tools.jackson.databind.JsonNode;

/**
 * 대화에서 ETF 종목을 바꾼 비교의 적용(#154, D-097). 결과 화면(COMPARE)과 같은 기준이다: F-4는 다른 비교와 같은 판정,
 * F-5는 새 plan_version에 종목을 저장하고 되돌리기는 직전 종목으로 돌아간다. 적재되지 않은 종목은 422 NOT_AVAILABLE이다.
 */
class ChatEtfProductApplyTest extends ChatComparisonTestSupport {

    /** 같은 DB를 쓰는 다른 테스트 클래스의 069500 라벨과 겹치지 않게 둔다. */
    private static final String KOSPI_LABEL = "test-kospi-chat-apply";

    @Autowired
    ReferenceDataLoader loader;

    @BeforeEach
    void loadKospi200() throws Exception {
        try (InputStream csv = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            loader.loadEtf(new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                    new VersionSpec(KOSPI_LABEL, LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터"), csv);
        }
    }

    private static ConditionOverride monthly(long amount) {
        return new ConditionOverride(amount, null, null, null, null, null);
    }

    /** 종목을 바꾼 대화 비교를 /internal/calc처럼 기록하고, ai-server가 넘긴 모양대로 RECALC_RESULT를 저장한다. */
    private long recalcWithEtfProduct(String etfProductCode, String storedChanges) {
        long calcRunId = compareConditions.compare(memberId, monthly(500_000L), etfProductCode).calcRunId();
        return insertRecalcResult(memberId, calcRunId, "COMPLETED", storedChanges);
    }

    private boolean applicable(long messageId) throws Exception {
        JsonNode body = json.readTree(comparison(cookie, messageId).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        return body.get("applicable").asBoolean();
    }

    private ResultActions apply(long messageId, long expectedRevision) throws Exception {
        return mockMvc.perform(post("/api/chat/messages/{id}/apply", messageId).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":" + expectedRevision + "}"));
    }

    private String currentEtfProductCode() {
        return jdbc.sql("SELECT v.etf_product_code FROM plan p JOIN plan_version v ON v.id = p.current_version_id "
                + "WHERE p.member_id = ?").param(memberId).query(String.class).single();
    }

    private long currentVersionId() {
        return jdbc.sql("SELECT current_version_id FROM plan WHERE member_id = ?").param(memberId).query(Long.class)
                .single();
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM plan WHERE member_id = ?").param(memberId).query(Long.class).single();
    }

    /** 그 버전을 만든 APPLY PlanChanged가 발행됐는지(이벤트 발행 기록). */
    private boolean planChangedPublished(long afterVersionId) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type LIKE '%PlanChanged' "
                        + "AND serialized_event LIKE ? AND serialized_event LIKE '%\"action\":\"APPLY\"%'")
                .param("%\"afterVersionId\":" + afterVersionId + ",%").query(Long.class).single() > 0;
    }

    private long versionCount() {
        return jdbc.sql("SELECT count(*) FROM plan_version WHERE member_id = ?").param(memberId).query(Long.class)
                .single();
    }

    @Test
    @DisplayName("종목을 바꾼 가장 최근 비교는 F-4 applicable이 true이고 conditions에 두 쪽 종목 코드가 있다")
    void marksEtfProductComparisonApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("069500",
                "{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}");

        assertThat(applicable(messageId)).isTrue();
        String body = comparison(cookie, messageId).andReturn().getResponse().getContentAsString();
        JsonNode conditions = json.readTree(body).get("conditions");
        assertThat(conditions.get("current").get("etfProductCode").asString()).isEqualTo("SP500");
        assertThat(conditions.get("changed").get("etfProductCode").asString()).isEqualTo("069500");
    }

    @Test
    @DisplayName("종목 키 없이 다른 조건만 바꾼 가장 최근 비교는 지금처럼 applicable이 true다(회귀 없음)")
    void keepsOtherComparisonsApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct(null, "{\"monthlySavingAmount\":500000}");

        assertThat(applicable(messageId)).isTrue();
    }

    @Test
    @DisplayName("종목을 바꾼 비교도 최신이 아니면 다른 비교처럼 applicable이 false다")
    void marksOlderEtfProductComparisonNotApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        long first = recalcWithEtfProduct("069500", "{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}");
        recalcWithEtfProduct(null, "{\"monthlySavingAmount\":400000}");

        assertThat(applicable(first)).isFalse();
    }

    @Test
    @DisplayName("종목을 바꾼 비교를 F-5로 적용하면 새 plan_version(CHAT)에 종목이 저장되고 PlanChanged가 발행되며, "
            + "되돌리면 직전 종목으로 돌아간다")
    void appliesEtfProductComparisonAndUndoRestoresIt() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("069500",
                "{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}");

        apply(messageId, saved.revision()).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(saved.revision() + 1));
        awaitEventsCompleted();

        assertThat(versionCount()).isEqualTo(2);
        assertThat(currentEtfProductCode()).isEqualTo("069500");
        long appliedVersionId = currentVersionId();
        Map<String, Object> created = jdbc.sql("SELECT source, monthly_saving_amount FROM plan_version WHERE id = ?")
                .param(appliedVersionId).query().singleRow();
        assertThat(created.get("source")).isEqualTo("CHAT");
        assertThat(((Number) created.get("monthly_saving_amount")).longValue()).isEqualTo(500_000L);
        assertThat(planChangedPublished(appliedVersionId)).isTrue();

        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision() + "}"))
                .andExpect(status().isOk());
        awaitEventsCompleted();

        assertThat(currentEtfProductCode()).isEqualTo("SP500");
    }

    @Test
    @DisplayName("계획과 같은 종목 키가 저장된 비교도 적용할 수 있고 종목은 그대로다")
    void appliesSameEtfProductKey() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("SP500", "{\"etfProductCode\":\"SP500\",\"monthlySavingAmount\":500000}");

        assertThat(applicable(messageId)).isTrue();
        apply(messageId, saved.revision()).andExpect(status().isOk());

        assertThat(currentEtfProductCode()).isEqualTo("SP500");
    }

    @Test
    @DisplayName("(예외) 저장된 종목이 적재되지 않은 종목이면 F-4는 500, F-5는 422 VALIDATION_FAILED(etfProductCode, "
            + "NOT_AVAILABLE)이고 계획은 바뀌지 않는다")
    void rejectsApplyingUnavailableEtfProduct() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("069500",
                "{\"etfProductCode\":\"999999\",\"monthlySavingAmount\":500000}");
        // F-4는 그 종목으로 다시 계산할 수 없어 재계산 실패(500)이고 카드·적용 버튼이 없다(C12b-6)
        comparison(cookie, messageId).andExpect(status().isInternalServerError());

        apply(messageId, saved.revision()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("etfProductCode"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_AVAILABLE"));
        assertThat(versionCount()).isEqualTo(1);
        assertThat(revision()).isEqualTo(saved.revision());
    }
}
