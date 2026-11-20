package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.PlanContextView;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.chat.application.ChatAnswerRequested;
import com.moeun.backend.plan.PlanSaveResult;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/**
 * #128: chat 어댑터가 calc.PlanContextProvider에 실제로 연결됐는지 확인한다. PlanContextPort를 스텁하지 않고
 * 실제 어댑터 → 실제 PlanContextProvider → 실제 계산·DB를 거쳐 ai-server 테스트 대역이 받은 요청의 planContext를 본다.
 */
class ChatPlanContextAdapterTest extends ChatComparisonTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final AiServerStub AI = AiServerStub.start();

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("moeun.ai-server.base-url", AI::baseUrl);
    }

    @Autowired
    PlanContextProvider provider;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void resetAiServer() {
        AI.reset();
        // 스텁의 기본은 요청을 잡아 두는 것이다. 이 테스트는 요청 본문만 보므로 바로 답하게 한다
        AI.respond(200, AiServerStub.ANSWER_OK);
    }

    @AfterEach
    void settle() {
        AI.awaitIdle();
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM event_publication "
                + "WHERE completion_date IS NULL").query(Long.class).single() == 0);
    }

    @Test
    @DisplayName("EXPLAIN은 기록과 저장된 기준 시나리오로 만든 planContext를 ai-server에 보내고 회원·계정 정보는 없다")
    void explainSendsPlanContextOfRecordedRunWithStoredBaseline() {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long calcRunId = latestPlanRunId(saved.planVersionId());
        // 저장된 기준 시나리오(GOOD)와 현재 계획의 기준 시나리오(AVG)가 다르다. 기록과 저장된 값을 써야 한다
        long messageId = insertExplanation(memberId, calcRunId, "GOOD");

        publishAnswerRequested(messageId, memberId);

        JsonNode request = awaitRequest();
        PlanContextView expected = provider.findForCalcRun(memberId, calcRunId, Scenario.GOOD).orElseThrow();
        assertThat(request.get("mode").asText()).isEqualTo("EXPLAIN");
        assertThat(request.get("planContext")).isEqualTo(wireForm(expected));
        assertThat(request.get("planContext").get("calcRunId").asLong()).isEqualTo(calcRunId);
        assertThat(request.get("planContext").get("result").get("baselineScenario").asText()).isEqualTo("GOOD");
        assertNoMemberOrAccount(request.get("planContext"));
    }

    @Test
    @DisplayName("계획이 있는 회원의 QUESTION은 현재 계획 기준 planContext를 ai-server에 보내고 회원·계정 정보는 없다")
    void questionSendsPlanContextOfCurrentPlan() throws Exception {
        savePlan(memberId, null, 300_000L);

        askText(cookie, "월 50만원으로 하면 어때요?").andExpect(status().isAccepted());

        JsonNode request = awaitRequest();
        PlanContextView expected = provider.findForCurrentPlan(memberId).orElseThrow();
        assertThat(request.get("mode").asText()).isEqualTo("QUESTION");
        assertThat(request.get("planContext")).isEqualTo(wireForm(expected));
        assertThat(request.get("planContext").get("conditions").get("monthlySavingAmount").asLong())
                .isEqualTo(300_000L);
        assertNoMemberOrAccount(request.get("planContext"));
    }

    @Test
    @DisplayName("(예외) 계획이 없는 회원의 QUESTION은 planContext: null로 나간다")
    void questionWithoutPlanSendsNullPlanContext() throws Exception {
        askText(cookie, "계획이 없어도 물어볼게요").andExpect(status().isAccepted());

        JsonNode request = awaitRequest();
        assertThat(request.get("mode").asText()).isEqualTo("QUESTION");
        assertThat(request.has("planContext")).isTrue();
        assertThat(request.get("planContext").isNull()).isTrue();
    }

    @Test
    @DisplayName("(예외) EXPLAIN 대상 기록이 내 PLAN 기록이 아니면 ai-server를 부르지 않고 FAILED·FAILURE가 된다")
    void explainOfUnknownRunFailsWithoutCallingAiServer() {
        PlanSaveResult others = savePlan(otherMemberId, null, 300_000L);
        long othersRunId = latestPlanRunId(others.planVersionId());
        long messageId = insertExplanation(memberId, othersRunId, "AVG");

        publishAnswerRequested(messageId, memberId);

        await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(messageId)));
        assertThat(jdbc.sql("SELECT message_type FROM chat_message WHERE id = ?").param(messageId)
                .query(String.class).single()).isEqualTo("FAILURE");
        assertThat(AI.requests()).isEmpty();
    }

    /** HTTP로 나가 ai-server 대역이 파싱한 것과 같은 형태(2.840 → 2.84)로 맞춘다. */
    private JsonNode wireForm(PlanContextView view) {
        return json.readTree(json.writeValueAsString(view));
    }

    private JsonNode awaitRequest() {
        await().atMost(WAIT).until(() -> !AI.requests().isEmpty());
        List<JsonNode> requests = AI.requests();
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private long latestPlanRunId(long planVersionId) {
        return jdbc.sql("SELECT id FROM calc_run WHERE plan_version_id = ? AND run_type = 'PLAN' "
                + "ORDER BY created_at DESC, id DESC LIMIT 1").param(planVersionId).query(Long.class).single();
    }

    private long insertExplanation(long member, long calcRunId, String baseline) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, calc_run_id, "
                        + "baseline_scenario) VALUES (?, 'ASSISTANT', 'EXPLANATION', 'PENDING', ?, ?) RETURNING id")
                .params(member, calcRunId, baseline).query(Long.class).single();
    }

    private void publishAnswerRequested(long assistantMessageId, long member) {
        transactions.executeWithoutResult(status ->
                publisher.publishEvent(new ChatAnswerRequested(assistantMessageId, member)));
    }

    private String statusOf(long messageId) {
        return jdbc.sql("SELECT status FROM chat_message WHERE id = ?").param(messageId)
                .query(String.class).optional().orElse("DELETED");
    }

    private ResultActions askText(Cookie who, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/messages").cookie(who).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", text))));
    }

    /** planContext 어디에도 회원·계정을 가리키는 키와 값이 없다(D-030). */
    private void assertNoMemberOrAccount(JsonNode planContext) {
        List<String> names = new ArrayList<>();
        collectNames(planContext, names);
        assertThat(names).noneMatch(name -> name.toLowerCase().contains("member")
                || name.toLowerCase().contains("login") || name.toLowerCase().contains("account")
                || name.toLowerCase().contains("password"));
        assertThat(planContext.toString()).doesNotContain("demo01").doesNotContain("demo02");
    }

    private void collectNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                names.add(entry.getKey());
                collectNames(entry.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectNames(child, names));
        }
    }
}
