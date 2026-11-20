package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.moeun.backend.calc.Scenario;
import com.moeun.backend.chat.application.PlanContextSnapshot;

import tools.jackson.databind.JsonNode;

/**
 * F-3 설명 요청 뒤 ai-server가 응답하지 못한 경우(#43 AC-6, FR-AI-04): 연결 실패·응답 시간 초과.
 * HTTP 응답이 없어도 같은 설명 행이 FAILED·FAILURE·chat.failed가 되고 이벤트는 완료되며 계획·계산 기록은 그대로다.
 * 응답 타임아웃을 1초로 줄인 컨텍스트에서 돈다(ChatAiServerTimeoutTest와 같은 설정이라 컨텍스트를 공유한다).
 */
@TestPropertySource(properties = "moeun.ai-server.read-timeout=1s")
class ChatExplanationAiFailureTest extends ChatIntegrationTestSupport {

    @BeforeEach
    void echoPlanContext() {
        // planContext는 정상으로 만들어지게 둔다. 실패 원인은 ai-server 호출뿐이다
        given(planContextPort.findForCalcRun(anyLong(), anyLong(), any())).willAnswer(invocation -> {
            long calcRunId = invocation.getArgument(1);
            Scenario baseline = invocation.getArgument(2);
            return Optional.of(new PlanContextSnapshot(baseline, JSON.readTree(
                    "{\"calcRunId\":%d,\"result\":{\"status\":\"CALCULATED\",\"baselineScenario\":\"%s\"}}"
                            .formatted(calcRunId, baseline.name()))));
        });
    }

    private long planRunOf(long member, String baseline) {
        long calcRun = insertCalcRun(member, "PLAN");
        long planVersion = jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?").param(calcRun)
                .query(Long.class).single();
        jdbc.sql("DELETE FROM plan WHERE member_id = ?").param(member).update();
        jdbc.sql("INSERT INTO plan (member_id, current_version_id, baseline_scenario) VALUES (?, ?, ?)")
                .params(member, planVersion, baseline).update();
        return calcRun;
    }

    private MvcResult explainRun(long calcRun) throws Exception {
        return mockMvc.perform(post("/api/chat/explanations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isAccepted()).andReturn();
    }

    private static void assertEmptyObject(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.isObject()).isTrue();
        assertThat(body.size()).isZero();
    }

    private long explanationRowOf(long calcRun) {
        return jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? AND calc_run_id = ? "
                        + "AND role = 'ASSISTANT' ORDER BY id")
                .params(memberId, calcRun).query(Long.class).single();
    }

    private void assertFailed(long id) {
        Map<String, Object> failed = row(id);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());
    }

    /** 그 회원의 plan_version·plan·calc_run 행 전체 값. */
    private List<Map<String, Object>> planAndCalcRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.sql("SELECT * FROM plan_version WHERE member_id = ? ORDER BY id").param(memberId)
                .query().listOfRows());
        rows.addAll(jdbc.sql("SELECT * FROM plan WHERE member_id = ?").param(memberId).query().listOfRows());
        rows.addAll(jdbc.sql("SELECT cr.* FROM calc_run cr JOIN plan_version pv ON pv.id = cr.plan_version_id "
                + "WHERE pv.member_id = ? ORDER BY cr.id").param(memberId).query().listOfRows());
        return rows;
    }

    @Test
    void connectionFailureOnExplainMarksSameExplanationFailed() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        List<Map<String, Object>> before = planAndCalcRows();
        AI.goDown();
        try {
            MvcResult result = explainRun(calcRun);

            assertEmptyObject(result);
            long explanationId = explanationRowOf(calcRun);
            awaitStatus(explanationId, "FAILED");
            awaitAnswerEventsCompleted();

            assertFailed(explanationId);
            verify(planContextPort, times(1)).findForCalcRun(memberId, calcRun, Scenario.AVG);
            assertThat(aiCallTransactionStates()).as("실제로 ai-server 호출을 시도했다").isNotEmpty();
            assertThat(answerEvents()).isEqualTo(1);
            assertThat(countRows(memberId)).isEqualTo(1);
            assertThat(planAndCalcRows()).isEqualTo(before);
        } finally {
            AI.comeUp();
        }
    }

    @Test
    void responseTimeoutOnExplainMarksSameExplanationFailed() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        List<Map<String, Object>> before = planAndCalcRows();
        // 응답 타임아웃(1초)보다 늦게 정상 답을 보낸다
        AI.respondAfter(Duration.ofSeconds(4), 200, JSON.writeValueAsString(Map.of("messageType", "EXPLANATION",
                "content", "늦은 설명", "baselineScenario", "AVG", "calcRunId", calcRun, "citationChunkIds",
                List.of())));

        MvcResult result = explainRun(calcRun);

        assertEmptyObject(result);
        long explanationId = explanationRowOf(calcRun);
        awaitStatus(explanationId, "FAILED");
        awaitAnswerEventsCompleted();
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);

        // 스텁이 늦은 답을 다 보낸 뒤에도 그 행은 실패 그대로다
        AI.awaitIdle();
        assertFailed(explanationId);
        assertThat(AI.requests()).hasSize(1);
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(planAndCalcRows()).isEqualTo(before);
    }
}
