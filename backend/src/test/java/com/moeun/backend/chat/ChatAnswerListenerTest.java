package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moeun.backend.calc.Scenario;
import com.moeun.backend.chat.application.PlanContextSnapshot;

import tools.jackson.databind.JsonNode;

/** ChatAnswerRequested 리스너: ai-server POST /chat 호출과 결과 저장 (#41, 계약 C12b-11-2·C12b-14·C1a-10). */
class ChatAnswerListenerTest extends ChatIntegrationTestSupport {

    private static final String PLAN_CONTEXT_GOOD = """
            {"calcRunId":77,"conditions":{"monthlySavingAmount":500000},
             "result":{"status":"CALCULATED","baselineScenario":"GOOD",
              "scenarios":[{"scenario":"GOOD","isAchieved":true}],
              "assumptions":{"isDefaultSavingsRate":true,"scenarioMethod":"CAGR","excludedItems":[]}},
             "display":{"goalAmount":"1억원"}}""";

    private static boolean nullOrMissing(JsonNode node, String field) {
        return !node.has(field) || node.get(field).isNull();
    }

    private void assertFailed(long id) {
        Map<String, Object> failed = row(id);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());
    }

    private long citationCount(long messageId) {
        return jdbc.sql("SELECT count(*) FROM chat_message_citation WHERE message_id = ?").param(messageId)
                .query(Long.class).single();
    }

    // ---------- AC-10, AC-11, AC-24 ----------

    @Test
    void questionSendsPlanContextFromPort() throws Exception {
        JsonNode planContext = JSON.readTree(PLAN_CONTEXT_GOOD);
        given(planContextPort.findForCurrentPlan(memberId))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.GOOD, planContext)));

        long assistantId = askAndGetAssistantId(cookie, "계획대로면 언제 도달해요?");
        JsonNode request = awaitAiRequests(1).get(0);

        assertThat(request.get("mode").asString()).isEqualTo("QUESTION");
        assertThat(request.get("memberId").asLong()).isEqualTo(memberId);
        assertThat(request.get("messageId").asLong()).isEqualTo(assistantId);
        assertThat(request.get("text").asString()).isEqualTo("계획대로면 언제 도달해요?");
        assertThat(request.get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(nullOrMissing(request, "calcRunId")).isTrue();
        assertThat(request.get("planContext")).isEqualTo(planContext);
        verify(planContextPort).findForCurrentPlan(memberId);
        verify(planContextPort, never()).findForCalcRun(anyLong(), anyLong(), any());
    }

    @Test
    void questionWithoutPlanSendsNullPlanContextAndAvg() throws Exception {
        given(planContextPort.findForCurrentPlan(memberId)).willReturn(Optional.empty());

        long assistantId = askAndGetAssistantId(cookie, "계획 없이 질문해요");
        JsonNode request = awaitAiRequests(1).get(0);

        assertThat(nullOrMissing(request, "planContext")).isTrue();
        assertThat(request.get("baselineScenario").asString()).isEqualTo("AVG");
        assertThat(nullOrMissing(request, "calcRunId")).isTrue();
        assertThat(!request.has("history") || request.get("history").isEmpty()).isTrue();

        AI.release(200, AiServerStub.ANSWER_OK);
        awaitStatus(assistantId, "COMPLETED");
        Map<String, Object> done = row(assistantId);
        assertThat(done.get("message_type")).isEqualTo("ANSWER");
        assertThat(done.get("content")).isEqualTo("스텁 답변");
    }

    // ---------- AC-7 ----------

    @Test
    void trimmedTextIsSentToAiServer() throws Exception {
        askAndGetAssistantId(cookie, "　  월 50만원이면?\n ");

        assertThat(awaitAiRequests(1).get(0).get("text").asString()).isEqualTo("월 50만원이면?");
    }

    // ---------- AC-12 ----------

    @Test
    void historyHasRecent20CompletedMessagesInOrder() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        List<String[]> completed = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            completed.add(insertTurn(i));
        }
        insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", "실패한 답");
        insertExplanation(memberId, "COMPLETED", "m23", calcRun, "AVG", Instant.now());
        completed.add(new String[] {"assistant", "m23"});
        completed.add(insertTurn(24));
        completed.add(insertTurn(25));
        insertExplanation(memberId, "PENDING", null, calcRun, "AVG", Instant.now());
        insertMessage(otherMemberId, "USER", "QUESTION", "COMPLETED", "남의 대화");

        askAndGetAssistantId(cookie, "새 질문");
        JsonNode history = awaitAiRequests(1).get(0).get("history");

        List<String[]> expected = completed.subList(completed.size() - 20, completed.size());
        assertThat(history.size()).isEqualTo(20);
        for (int i = 0; i < 20; i++) {
            assertThat(history.get(i).get("role").asString()).isEqualTo(expected.get(i)[0]);
            assertThat(history.get(i).get("content").asString()).isEqualTo(expected.get(i)[1]);
        }
    }

    @ParameterizedTest(name = "{displayName} › {0}")
    @ValueSource(ints = {2, 20, 21})
    void historyKeepsCompletedMessagesUpTo20(int existing) throws Exception {
        List<String[]> completed = new ArrayList<>();
        for (int i = 1; i <= existing; i++) {
            completed.add(insertTurn(i));
        }

        askAndGetAssistantId(cookie, "이어지는 질문");
        JsonNode history = awaitAiRequests(1).get(0).get("history");

        List<String[]> expected = completed.subList(Math.max(0, existing - 20), existing);
        assertThat(history).isNotNull();
        assertThat(history.size()).isEqualTo(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(history.get(i).get("role").asString()).isEqualTo(expected.get(i)[0]);
            assertThat(history.get(i).get("content").asString()).isEqualTo(expected.get(i)[1]);
        }
    }

    private String[] insertTurn(int turn) {
        boolean user = turn % 2 == 1;
        insertMessage(memberId, user ? "USER" : "ASSISTANT", user ? "QUESTION" : "ANSWER", "COMPLETED", "m" + turn);
        return new String[] {user ? "user" : "assistant", "m" + turn};
    }

    // ---------- AC-13 ----------

    @Test
    void explainUsesStoredBaselineAndCalcRun() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long explanationId = insertExplanation(memberId, "PENDING", null, calcRun, "BAD", Instant.now());
        JsonNode planContext = JSON.readTree("""
                {"calcRunId":%d,"result":{"status":"CALCULATED","baselineScenario":"BAD","scenarios":[],
                 "assumptions":{"isDefaultSavingsRate":true,"scenarioMethod":null,"excludedItems":null}},
                 "display":{}}""".formatted(calcRun));
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.BAD))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.BAD, planContext)));

        publishAnswerRequested(explanationId, memberId);
        JsonNode request = awaitAiRequests(1).get(0);

        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(request.get("calcRunId").asLong()).isEqualTo(calcRun);
        assertThat(request.get("baselineScenario").asString()).isEqualTo("BAD");
        assertThat(request.get("planContext")).isEqualTo(planContext);
        assertThat(request.get("planContext").get("result").get("baselineScenario").asString()).isEqualTo("BAD");
        verify(planContextPort).findForCalcRun(memberId, calcRun, Scenario.BAD);
        verify(planContextPort, never()).findForCurrentPlan(anyLong());

        AI.release(200, """
                {"messageType":"EXPLANATION","content":"설명","baselineScenario":"BAD","calcRunId":%d,
                 "conditionChanges":null,"citationChunkIds":[]}""".formatted(calcRun));
        awaitStatus(explanationId, "COMPLETED");
        assertThat(row(explanationId).get("content")).isEqualTo("설명");
    }

    /** EXPLAIN에 ai-server가 정상 OUT_OF_SCOPE(calcRunId 없음)로 답하면 실패가 아니라 그 답으로 완료한다. */
    @Test
    void explainOutOfScopeAnswerCompletesWithoutCalcRun() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long explanationId = insertExplanation(memberId, "PENDING", null, calcRun, "BAD", Instant.now());
        JsonNode planContext = JSON.readTree("""
                {"calcRunId":%d,"result":{"status":"CALCULATED","baselineScenario":"BAD","scenarios":[],
                 "assumptions":{"isDefaultSavingsRate":true,"scenarioMethod":null,"excludedItems":null}},
                 "display":{}}""".formatted(calcRun));
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.BAD))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.BAD, planContext)));

        publishAnswerRequested(explanationId, memberId);
        assertThat(awaitAiRequests(1).get(0).get("mode").asString()).isEqualTo("EXPLAIN");

        AI.release(200, """
                {"messageType":"OUT_OF_SCOPE","content":"그 질문에는 답하기 어려워요","baselineScenario":"BAD",
                 "calcRunId":null,"conditionChanges":null,"citationChunkIds":[]}""");
        awaitStatus(explanationId, "COMPLETED");
        awaitAnswerEventsCompleted();

        Map<String, Object> done = row(explanationId);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("message_type")).isEqualTo("OUT_OF_SCOPE");
        assertThat(done.get("content")).isEqualTo("그 질문에는 답하기 어려워요");
        assertThat(done.get("calc_run_id")).isNull();
        assertThat(citationCount(explanationId)).isZero();
        assertThat(incompleteAnswerEvents()).isZero();
    }

    // ---------- AC-14 ----------

    @Test
    void planContextFailureMarksFailedWithoutCallingAiServer() throws Exception {
        given(planContextPort.findForCurrentPlan(anyLong())).willThrow(new IllegalStateException("기준 데이터 오류"));

        long assistantId = askAndGetAssistantId(cookie, "계산이 깨진 계획에 질문");
        awaitStatus(assistantId, "FAILED");
        awaitAnswerEventsCompleted();

        assertFailed(assistantId);
        assertThat(AI.requests()).isEmpty();
        assertThat(answerEvents()).isGreaterThanOrEqualTo(1);
        assertThat(incompleteAnswerEvents()).isZero();
    }

    @Test
    void explainPlanContextFailureMarksFailedWithoutCallingAiServer() {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long explanationId = insertExplanation(memberId, "PENDING", null, calcRun, "BAD", Instant.now());
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.BAD))
                .willThrow(new IllegalStateException("엔진 오류"));

        publishAnswerRequested(explanationId, memberId);
        awaitStatus(explanationId, "FAILED");
        awaitAnswerEventsCompleted();

        assertFailed(explanationId);
        assertThat(AI.requests()).isEmpty();
        verify(planContextPort).findForCalcRun(memberId, calcRun, Scenario.BAD);
    }

    // ---------- AC-2 ----------

    @Test
    void badOutputErrorMarksFailed() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long planVersion = jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?").param(calcRun)
                .query(Long.class).single();
        jdbc.sql("DELETE FROM plan WHERE member_id = ?").param(memberId).update();
        jdbc.sql("INSERT INTO plan (member_id, current_version_id, baseline_scenario) VALUES (?, ?, 'AVG')")
                .params(memberId, planVersion).update();
        List<Map<String, Object>> before = planAndCalcRows();
        AI.respond(502, "{\"error\":\"BAD_OUTPUT\"}");

        long assistantId = askAndGetAssistantId(cookie, "질문");
        awaitStatus(assistantId, "FAILED");
        awaitAnswerEventsCompleted();

        assertFailed(assistantId);
        // 계산 결과 화면의 원천(계획 조건·현재 계획·계산 기록)은 행 수뿐 아니라 값도 그대로다(사람 결정 B1)
        assertThat(planAndCalcRows()).isEqualTo(before);
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
    void badOutputBodyWithOkStatusMarksFailed() throws Exception {
        AI.respond(200, "{\"error\":\"BAD_OUTPUT\"}");

        long assistantId = askAndGetAssistantId(cookie, "질문");
        awaitStatus(assistantId, "FAILED");

        assertFailed(assistantId);
    }

    // ---------- AC-15 ----------

    @Test
    void aiServerErrorStatusesMarkFailed() throws Exception {
        Map<Integer, String> errors = Map.of(
                400, "{\"error\":\"BAD_REQUEST\"}",
                422, "{\"detail\":[{\"loc\":[\"body\",\"planContext\"],\"msg\":\"invalid\"}]}",
                502, "{\"error\":\"LLM_UNAVAILABLE\"}");
        for (Map.Entry<Integer, String> error : errors.entrySet()) {
            AI.respond(error.getKey(), error.getValue());

            long assistantId = askAndGetAssistantId(cookie, "질문 " + error.getKey());
            awaitStatus(assistantId, "FAILED");

            assertFailed(assistantId);
        }
    }

    // ---------- AC-16 ----------

    @Test
    void noTransactionIsOpenDuringAiServerCall() throws Exception {
        long assistantId = askAndGetAssistantId(cookie, "트랜잭션 확인");
        awaitAiRequests(1);

        // HTTP 요청을 만든 그 스레드에 Spring 트랜잭션이 없어야 한다(첫 SQL 전이라도 열린 트랜잭션이면 실패)
        assertThat(aiCallTransactionStates()).isNotEmpty().containsOnly(false);
        long openTransactions = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND pid <> pg_backend_pid() AND state LIKE 'idle in transaction%'")
                .query(Long.class).single();
        assertThat(openTransactions).isZero();

        AI.release(200, AiServerStub.ANSWER_OK);
        awaitStatus(assistantId, "COMPLETED");
        awaitAnswerEventsCompleted();
        assertThat(answerEvents()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noTransactionDuringAiCallWhenQuestionHasPlanContext() throws Exception {
        JsonNode planContext = JSON.readTree(PLAN_CONTEXT_GOOD);
        given(planContextPort.findForCurrentPlan(memberId))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.GOOD, planContext)));

        long assistantId = askAndGetAssistantId(cookie, "계획 있는 질문의 트랜잭션 확인");
        assertThat(awaitAiRequests(1).get(0).get("planContext")).isEqualTo(planContext);

        assertThat(aiCallTransactionStates()).isNotEmpty().containsOnly(false);

        AI.release(200, AiServerStub.ANSWER_OK);
        awaitStatus(assistantId, "COMPLETED");
        awaitAnswerEventsCompleted();
    }

    @Test
    void noTransactionDuringAiCallForExplain() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long explanationId = insertExplanation(memberId, "PENDING", null, calcRun, "GOOD", Instant.now());
        JsonNode planContext = JSON.readTree("""
                {"calcRunId":%d,"result":{"status":"CALCULATED","baselineScenario":"GOOD","scenarios":[],
                 "assumptions":{"isDefaultSavingsRate":true,"scenarioMethod":null,"excludedItems":null}},
                 "display":{}}""".formatted(calcRun));
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.GOOD))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.GOOD, planContext)));

        publishAnswerRequested(explanationId, memberId);
        assertThat(awaitAiRequests(1).get(0).get("planContext")).isEqualTo(planContext);

        assertThat(aiCallTransactionStates()).isNotEmpty().containsOnly(false);

        AI.release(200, """
                {"messageType":"EXPLANATION","content":"설명","baselineScenario":"GOOD","calcRunId":%d,
                 "conditionChanges":null,"citationChunkIds":[]}""".formatted(calcRun));
        awaitStatus(explanationId, "COMPLETED");
        awaitAnswerEventsCompleted();
    }

    // ---------- AC-17 ----------

    @Test
    void nonPendingMessageIsNotSentToAiServer() {
        long done = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "이미 끝난 답");

        publishAnswerRequested(done, memberId);
        awaitAnswerEventsCompleted();

        assertThat(AI.requests()).isEmpty();
        assertThat(row(done).get("status")).isEqualTo("COMPLETED");
        assertThat(row(done).get("content")).isEqualTo("이미 끝난 답");
    }

    @Test
    void failedMessageIsNotSentToAiServer() {
        long failed = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", failedSentence());

        publishAnswerRequested(failed, memberId);
        awaitAnswerEventsCompleted();

        assertThat(AI.requests()).isEmpty();
        Map<String, Object> kept = row(failed);
        assertThat(kept.get("status")).isEqualTo("FAILED");
        assertThat(kept.get("message_type")).isEqualTo("FAILURE");
        assertThat(kept.get("content")).isEqualTo(failedSentence());
        verify(planContextPort, never()).findForCurrentPlan(anyLong());
    }

    @Test
    void deletedMessageIsNotSentToAiServer() {
        publishAnswerRequested(Long.MAX_VALUE, memberId);
        awaitAnswerEventsCompleted();

        assertThat(AI.requests()).isEmpty();
        assertThat(countRows(memberId)).isZero();
    }

    // ---------- AC-18 ----------

    @Test
    void completedAnswerSavesCitations() throws Exception {
        List<Long> chunks = insertChunks();
        long assistantId = askAndGetAssistantId(cookie, "근거 있는 질문");
        awaitAiRequests(1);

        AI.release(200, """
                {"messageType":"ANSWER","content":"근거 답변","baselineScenario":"AVG","calcRunId":null,
                 "conditionChanges":null,"citationChunkIds":[%d,%d]}""".formatted(chunks.get(0), chunks.get(1)));
        awaitStatus(assistantId, "COMPLETED");
        awaitAnswerEventsCompleted();

        List<Long> cited = jdbc.sql("SELECT chunk_id FROM chat_message_citation WHERE message_id = ? ORDER BY rank")
                .param(assistantId).query(Long.class).list();
        assertThat(cited).containsExactly(chunks.get(0), chunks.get(1));
    }

    @Test
    void responseAfterDeletionIsDiscarded() throws Exception {
        List<Long> chunks = insertChunks();
        long assistantId = askAndGetAssistantId(cookie, "로그아웃 직전 질문");
        awaitAiRequests(1);
        jdbc.sql("DELETE FROM chat_message WHERE member_id = ?").param(memberId).update();

        AI.release(200, """
                {"messageType":"ANSWER","content":"늦은 답변","baselineScenario":"AVG","calcRunId":null,
                 "conditionChanges":null,"citationChunkIds":[%d]}""".formatted(chunks.get(0)));
        awaitAnswerEventsCompleted();

        assertThat(countRows(memberId)).isZero();
        assertThat(citationCount(assistantId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM chat_message_citation").query(Long.class).single()).isZero();
    }

    @Test
    void responseAfterCleanupIsDiscarded() throws Exception {
        List<Long> chunks = insertChunks();
        long assistantId = askAndGetAssistantId(cookie, "정리된 뒤 답이 오는 질문");
        awaitAiRequests(1);
        jdbc.sql("UPDATE chat_message SET status = 'FAILED', message_type = 'FAILURE', content = ? WHERE id = ?")
                .params(failedSentence(), assistantId).update();

        AI.release(200, """
                {"messageType":"ANSWER","content":"늦은 답변","baselineScenario":"AVG","calcRunId":null,
                 "conditionChanges":null,"citationChunkIds":[%d]}""".formatted(chunks.get(0)));
        awaitAnswerEventsCompleted();

        assertFailed(assistantId);
        assertThat(citationCount(assistantId)).isZero();
    }

    // ---------- AC-19 ----------

    @Test
    void recalcResultConditionChangesAreSavedInCamelCase() throws Exception {
        long chatRun = insertCalcRun(memberId, "CHAT");
        long assistantId = askAndGetAssistantId(cookie, "월 150만원, 적금 60%, 금리 3.5%, 24개월이면?");
        awaitAiRequests(1);

        AI.release(200, """
                {"messageType":"RECALC_RESULT","content":"재계산 결과","baselineScenario":"AVG","calcRunId":%d,
                 "conditionChanges":{"monthly_saving_amount":1500000,"savings_ratio_pct":60,
                  "savings_rate_input_pct":3.5,"target_period_months":24,"unknown_key":1},
                 "citationChunkIds":[]}""".formatted(chatRun));
        awaitStatus(assistantId, "COMPLETED");

        Map<String, Object> saved = row(assistantId);
        assertThat(saved.get("message_type")).isEqualTo("RECALC_RESULT");
        assertThat(saved.get("calc_run_id")).isEqualTo(chatRun);
        assertThat(JSON.readTree((String) saved.get("condition_changes"))).isEqualTo(JSON.readTree("""
                {"monthlySavingAmount":1500000,"savingsRatioPct":60,"savingsRateInputPct":3.5,
                 "targetPeriodMonths":24,"unknown_key":1}"""));
    }

    // ---------- AC-3 ----------

    @Test
    void requestFactoryUsesDefaultTimeouts() {
        assertThat(connectTimeoutOfAiClient()).isEqualTo(Duration.ofSeconds(2));
        assertThat(readTimeoutOfAiClient()).isEqualTo(Duration.ofSeconds(30));
    }
}
