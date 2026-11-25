package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.moeun.backend.calc.Scenario;
import com.moeun.backend.chat.application.PlanContextSnapshot;

import tools.jackson.databind.JsonNode;

/** F-1 질문 보내기 POST /api/chat/messages (#41, 계약 C12b-4·C12b-9·C12b-11-3). */
class ChatQuestionApiTest extends ChatIntegrationTestSupport {

    // ---------- AC-1 ----------

    @Test
    void questionReturns202WithMessageIdsOnly() throws Exception {
        MvcResult result = askText(cookie, "월 50만원으로 하면 어때요?")
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("userMessageId", "assistantMessageId");
        long userId = body.get("userMessageId").asLong();
        long assistantId = body.get("assistantMessageId").asLong();

        Map<String, Object> user = row(userId);
        assertThat(user.get("member_id")).isEqualTo(memberId);
        assertThat(user.get("role")).isEqualTo("USER");
        assertThat(user.get("message_type")).isEqualTo("QUESTION");
        assertThat(user.get("status")).isEqualTo("COMPLETED");
        assertThat(user.get("content")).isEqualTo("월 50만원으로 하면 어때요?");

        Map<String, Object> assistant = row(assistantId);
        assertThat(assistant.get("member_id")).isEqualTo(memberId);
        assertThat(assistant.get("role")).isEqualTo("ASSISTANT");
        assertThat(assistant.get("message_type")).isEqualTo("ANSWER");
        assertThat(assistant.get("status")).isEqualTo("PENDING");
        assertThat(assistant.get("content")).isNull();
        assertThat(countRows(memberId)).isEqualTo(2);
    }

    @Test
    void questionWithoutLoginReturns401AndSavesNothing() throws Exception {
        mockMvc.perform(post("/api/chat/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"질문\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(jdbc.sql("SELECT count(*) FROM chat_message").query(Long.class).single()).isZero();
    }

    // ---------- AC-4 ----------

    @Test
    void secondQuestionWhileAnswerPendingReturns409() throws Exception {
        askText(cookie, "첫 질문").andExpect(status().isAccepted());

        askText(cookie, "두 번째 질문")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_PENDING"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(countRows(memberId)).isEqualTo(2);
    }

    @Test
    void explanationPendingDoesNotBlockQuestion() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        insertExplanation(memberId, "PENDING", null, calcRun, "AVG", Instant.now());

        askText(cookie, "설명 기다리는 중에 질문").andExpect(status().isAccepted());

        assertThat(countRows(memberId)).isEqualTo(3);
    }

    @Test
    void otherMembersPendingOrFailedAnswerDoesNotBlockQuestion() throws Exception {
        insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null);
        insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", "실패");

        askText(cookie, "질문").andExpect(status().isAccepted());
    }

    // ---------- AC-5 ----------

    @Test
    void textOver2000AfterTrimReturns422OutOfRange() throws Exception {
        askText(cookie, "  " + "가".repeat(2001) + "  ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("text"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].min").value(1))
                .andExpect(jsonPath("$.errors[0].max").value(2000))
                .andExpect(jsonPath("$.errors[0].unit").value("CHAR"));

        assertThat(countRows(memberId)).isZero();
    }

    @Test
    void text2000AfterTrimIsAccepted() throws Exception {
        askText(cookie, "  " + "가".repeat(2000) + "\n").andExpect(status().isAccepted());

        List<Map<String, Object>> rows = rowsOf(memberId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("content")).isEqualTo("가".repeat(2000));
    }

    // ---------- AC-6 ----------

    @Test
    void blankTextReturns422RequiredAndSavesNothing() throws Exception {
        List<String> bodies = List.of("{\"text\":\"\"}", "{\"text\":\"   \"}", "{\"text\":\"\\n\\t \"}",
                "{\"text\":\"　　\"}", "{\"text\":null}", "{}");
        for (String body : bodies) {
            ask(cookie, body)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("text"))
                    .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
        }

        assertThat(countRows(memberId)).isZero();
    }

    // ---------- AC-7 ----------

    @Test
    void textIsTrimmedBeforeSave() throws Exception {
        askText(cookie, " 　 월 50만원이면? \t\n").andExpect(status().isAccepted());

        assertThat(rowsOf(memberId).get(0).get("content")).isEqualTo("월 50만원이면?");
    }

    @Test
    void surrogatePairsCountAsTwoCharacters() throws Exception {
        String emoji = "😀";
        askText(cookie, emoji.repeat(1000) + "a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"));
        assertThat(countRows(memberId)).isZero();

        askText(cookie, emoji.repeat(1000)).andExpect(status().isAccepted());
        assertThat(rowsOf(memberId).get(0).get("content")).isEqualTo(emoji.repeat(1000));
    }

    // ---------- AC-8 ----------

    @Test
    void malformedJsonReturns400() throws Exception {
        for (String body : List.of("{\"text\":", "[\"질문\"]", "\"질문\"")) {
            ask(cookie, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }

        assertThat(countRows(memberId)).isZero();
    }

    @Test
    void databaseErrorReturns500InternalErrorAndSavesNothing() throws Exception {
        String constraint = "ALTER TABLE chat_message ADD CONSTRAINT ck_chat_test_block "
                + "CHECK (role = 'USER' OR member_id <> " + memberId + ") NOT VALID";
        jdbc.sql(constraint).update();
        try {
            askText(cookie, "DB가 거절하는 질문")
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.errors").isEmpty())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("ck_chat_test_block"));
        } finally {
            jdbc.sql("ALTER TABLE chat_message DROP CONSTRAINT IF EXISTS ck_chat_test_block").update();
        }

        assertThat(countRows(memberId)).isZero();
    }

    // ---------- AC-9 ----------

    @Test
    void stalePendingIsFailedBeforePendingCheck() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long calcRun = insertCalcRun(memberId, "PLAN");
        long staleExplanation = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(61)));
        long othersStale = insertMessageAt(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(120)));

        askText(cookie, "오래 기다렸어요").andExpect(status().isAccepted());

        for (long id : List.of(staleAnswer, staleExplanation)) {
            Map<String, Object> cleaned = row(id);
            assertThat(cleaned.get("status")).isEqualTo("FAILED");
            assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
            assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        }
        assertThat(row(othersStale).get("status")).isEqualTo("PENDING");
    }

    @Test
    void pendingWithin60SecondsStillBlocks() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long recent = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(59)));

        askText(cookie, "아직 기다리는 중")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_PENDING"));

        Map<String, Object> kept = row(recent);
        assertThat(kept.get("status")).isEqualTo("PENDING");
        assertThat(kept.get("message_type")).isEqualTo("ANSWER");
        assertThat(kept.get("content")).isNull();
        assertThat(countRows(memberId)).isEqualTo(1);
    }

    /** 정리 조건은 `created_at < now − 60초`(엄격히 작음)다. 정확히 60초 된 행은 정리하지 않는다. */
    @Test
    void pendingExactly60SecondsOldStillBlocks() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long boundary = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(60)));

        askText(cookie, "정확히 60초째 질문")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_PENDING"));

        Map<String, Object> kept = row(boundary);
        assertThat(kept.get("status")).isEqualTo("PENDING");
        assertThat(kept.get("message_type")).isEqualTo("ANSWER");
        assertThat(kept.get("content")).isNull();
        assertThat(countRows(memberId)).isEqualTo(1);
    }

    /**
     * 60초를 1나노초라도 넘으면 정리한다. 행 시각은 마이크로초(DB 정밀도)에 맞춰 두고 시계만 1ns 앞으로 옮긴다.
     * 기준 시각을 JDBC로 넘기며 나노초가 잘려 "정확히 60초"와 같아지면 이 테스트가 실패한다.
     */
    @Test
    void pendingJustOver60SecondsIsCleaned() throws Exception {
        Instant rowAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofSeconds(60));
        clock.fixAt(rowAt.plus(Duration.ofSeconds(60)).plusNanos(1));
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null, rowAt);

        askText(cookie, "60초 하고 조금 지난 질문").andExpect(status().isAccepted());

        Map<String, Object> cleaned = row(stale);
        assertThat(cleaned.get("status")).isEqualTo("FAILED");
        assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
        assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        assertThat(countRows(memberId)).isEqualTo(3);
    }

    // ---------- AC-21 ----------

    @Test
    void alreadyExceededPlanQuestionIsAcceptedWithoutFixedSentence() throws Exception {
        JsonNode exceeded = JSON.readTree("""
                {"calcRunId":70,"result":{"status":"ALREADY_EXCEEDED","baselineScenario":"AVG","scenarios":[],
                 "assumptions":{"isDefaultSavingsRate":true,"scenarioMethod":null,"excludedItems":null}},
                 "display":{"goalAmount":"1억원"}}""");
        given(planContextPort.findForCurrentPlan(anyLong()))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.AVG, exceeded)));

        long assistantId = askAndGetAssistantId(cookie, "초과했는데 질문해요");
        awaitAiRequests(1);
        AI.release(200, AiServerStub.ANSWER_OK);
        awaitStatus(assistantId, "COMPLETED");

        List<Map<String, Object>> rows = rowsOf(memberId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("content")).doesNotContain(copy.get("chat.alreadyExceeded"));
    }
}
