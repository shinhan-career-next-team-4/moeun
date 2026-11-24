package com.moeun.backend.calc.web;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.calc.ChatComparisonQuery;
import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화로 목표 이름을 바꾸는 비교(#345). 이름은 계산에 쓰지 않지만 F-5 적용이 기준 계획 버전을 찾을 수 있게 이름만 바꿔도
 * 계산하고 calc_run(CHAT)을 남긴다. 이름은 입력 화면과 같은 규칙(앞뒤 공백을 뗀 1~100자, D-074)으로 검증한다.
 */
@SpringBootTest(properties = {"moeun.ai-server.internal-api-key=test-internal-key",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalCalcGoalNameTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    ChatComparisonQuery chatComparisons;

    long memberId;

    @BeforeEach
    void setUp() {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member WHERE login_id = 'internal-calc-goal-name'").update();
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('internal-calc-goal-name', 'x') "
                + "RETURNING id").query(Long.class).single();
        // 목표 내 집 마련 2,000만원, 36개월, 보유 500만원, 월 30만원, 기본금리, 비중 5:5
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36).heldAssets(5_000_000L)
                .monthlySavingAmount(300_000L).build(), null);
        awaitEventsCompleted();
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private ResultActions postChanges(String changesJson) throws Exception {
        return mockMvc.perform(post("/internal/calc").header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":" + memberId + ",\"conditionChanges\":" + changesJson + "}"));
    }

    private static JsonNode readBody(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long countChatRuns() {
        return jdbc.sql("SELECT count(*) FROM calc_run WHERE run_type = 'CHAT'").query(Long.class).single();
    }

    @Test
    @DisplayName("이름만 바꿔도 계산해 calc_run(CHAT)을 남기고, 두 쪽 결과는 같다")
    void comparesGoalNameOnly() throws Exception {
        JsonNode body = readBody(postChanges("{\"goalCustomName\":\"가게 보증금\"}").andExpect(status().isOk()));

        assertThat(countChatRuns()).isEqualTo(1);
        assertThat(body.get("changed").get("scenarios")).isEqualTo(body.get("current").get("scenarios"));
        assertThat(body.get("conditions").get("changed")).isEqualTo(body.get("conditions").get("current"));
    }

    @Test
    @DisplayName("이름과 목표 금액을 함께 바꾸면 목표 금액만 계산에 반영한다")
    void comparesGoalNameWithGoalAmount() throws Exception {
        JsonNode body = readBody(postChanges("{\"goalCustomName\":\"가게 보증금\",\"goalAmount\":50000000}")
                .andExpect(status().isOk()));

        assertThat(body.get("changed").get("goalAmount").asLong()).isEqualTo(50_000_000L);
        assertThat(body.get("current").get("goalAmount").asLong()).isEqualTo(20_000_000L);
    }

    @Test
    @DisplayName("(예외) 공백뿐이거나 100자를 넘는 이름은 422 VALIDATION_FAILED(goalCustomName, OUT_OF_RANGE)이고 기록이 없다")
    void rejectsBlankOrTooLongName() throws Exception {
        for (String name : new String[] {"   ", "가".repeat(101)}) {
            JsonNode problem = readBody(postChanges("{\"goalCustomName\":\"" + name + "\"}")
                    .andExpect(status().isUnprocessableContent()));

            assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
            JsonNode error = problem.get("errors").get(0);
            assertThat(error.get("field").asString()).isEqualTo("goalCustomName");
            assertThat(error.get("reason").asString()).isEqualTo("OUT_OF_RANGE");
        }
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("(예외) 이름이 문자열이 아니면 400 MALFORMED_REQUEST이고 기록이 없다")
    void rejectsNonStringName() throws Exception {
        JsonNode problem = readBody(postChanges("{\"goalCustomName\":123}").andExpect(status().isBadRequest()));

        assertThat(problem.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("이름을 바꾼 CHAT 기록을 recompute로 다시 계산하면 그때와 display가 같다")
    void recomputesGoalNameComparison() throws Exception {
        JsonNode body = readBody(postChanges("{\"goalCustomName\":\"가게 보증금\",\"goalAmount\":50000000}")
                .andExpect(status().isOk()));

        ChatComparisonView view = chatComparisons.recompute(memberId, body.get("calcRunId").asLong(),
                Map.of("goalCustomName", JSON.readTree("\"가게 보증금\""), "goalAmount", JSON.readTree("50000000")))
                .orElseThrow();

        assertThat(view.display()).isEqualTo(JSON.convertValue(body.get("display"), Map.class));
    }
}
