package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-3·P-4 응답의 {@code undoAvailable}(#263, D-095). 결과 화면의 '직전 계획으로 되돌리기' 버튼을 보일지 정한다.
 * 서버의 직전 계획(previous_version_id) 유무 그대로다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class UndoAvailableApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    Cookie cookie;

    @BeforeEach
    void login() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    private JsonNode savePlan(Long expectedRevision, long monthlySaving) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRevision", expectedRevision);
        body.put("goalType", "HOUSE");
        body.put("goalCustomName", null);
        body.put("goalAmount", 20_000_000L);
        body.put("targetPeriodMonths", 36);
        body.put("heldAssets", 5_000_000L);
        body.put("monthlySavingAmount", monthlySaving);
        body.put("savingsRateInputPct", null);
        String response = mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        awaitEventsCompleted();
        return JSON.readTree(response);
    }

    private JsonNode getPlan() throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/plan").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void postOk(String path, String body) throws Exception {
        mockMvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        awaitEventsCompleted();
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    @Test
    @DisplayName("첫 저장 직후에는 되돌릴 직전 계획이 없다")
    void firstSaveHasNothingToUndo() throws Exception {
        JsonNode saved = savePlan(null, 300_000L);

        assertThat(saved.get("undoAvailable").asBoolean()).isFalse();
        assertThat(getPlan().get("undoAvailable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("입력 수정 저장 뒤에는 직전 계획이 있다(P-3 응답·P-4 둘 다)")
    void inputEditLeavesPreviousPlan() throws Exception {
        savePlan(null, 300_000L);

        JsonNode saved = savePlan(1L, 400_000L);

        assertThat(saved.get("undoAvailable").asBoolean()).isTrue();
        assertThat(getPlan().get("undoAvailable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("비교 적용(P-6) 뒤에는 직전 계획이 있고, 되돌린 뒤에는 없다(1단계, D-068)")
    void applyThenUndo() throws Exception {
        savePlan(null, 300_000L);

        postOk("/api/plan/apply",
                "{\"path\":\"COMPARE\",\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":500000}}");
        assertThat(getPlan().get("undoAvailable").asBoolean()).isTrue();

        postOk("/api/plan/undo", "{\"expectedRevision\":2}");
        assertThat(getPlan().get("undoAvailable").asBoolean()).isFalse();
    }
}
