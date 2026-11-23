package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import tools.jackson.databind.JsonNode;

/**
 * 대화에서 ETF 종목을 바꾼 비교는 #154(계획에 종목 저장) 전까지 적용할 수 없다(#268, D-097). F-4는
 * {@code applicable: false}, F-5는 대화 경로 규칙으로 422 FIELD_NOT_CHANGEABLE이고 계획은 바뀌지 않는다.
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

    private long versionCount() {
        return jdbc.sql("SELECT count(*) FROM plan_version WHERE member_id = ?").param(memberId).query(Long.class)
                .single();
    }

    @Test
    @DisplayName("종목을 바꾼 가장 최근 비교는 F-4 applicable이 false이고 conditions에 두 쪽 종목 코드가 있다")
    void marksEtfProductComparisonNotApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("069500",
                "{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}");

        assertThat(applicable(messageId)).isFalse();
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
    @DisplayName("계획과 같은 종목 키가 저장돼 있어도 F-5가 거절하므로 F-4도 applicable이 false다")
    void marksSameEtfProductKeyNotApplicable() throws Exception {
        savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("SP500", "{\"etfProductCode\":\"SP500\",\"monthlySavingAmount\":500000}");

        assertThat(applicable(messageId)).isFalse();
    }

    @Test
    @DisplayName("(예외) 종목을 바꾼 비교를 F-5로 적용하면 422 FIELD_NOT_CHANGEABLE이고 계획은 바뀌지 않는다")
    void rejectsApplyingEtfProductComparison() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalcWithEtfProduct("069500",
                "{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}");

        mockMvc.perform(post("/api/chat/messages/{id}/apply", messageId).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + saved.revision() + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("etfProductCode"));
        assertThat(versionCount()).isEqualTo(1);
    }
}
