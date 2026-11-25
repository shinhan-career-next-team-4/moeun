package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.calc.application.CurrentPlanResultService;
import com.moeun.backend.chat.application.ChatAnswerRequested;
import com.moeun.backend.chat.application.PlanContextSnapshot;

import tools.jackson.databind.JsonNode;

/**
 * F-3 처리 순서(#43, 계약 H-4·C12b-11-3): 오래된 PENDING 정리 → findPlanRun → 설명 행 저장 → 발행.
 * 실제 calc.PlanContextProvider를 spy로 감싸, findPlanRun이 불리는 순간의 DB 상태를 기록한다.
 * ChatAnswerRequested는 발행 스레드에서 동기로 듣는 관찰자로 발행 순간의 트랜잭션 상태를 기록한다.
 */
class ChatExplanationOrderTest extends ChatIntegrationTestSupport {

    /** 발행 순간: 이벤트의 행 id, 트랜잭션이 열려 있었는지, 같은 트랜잭션에서 본 상태, 독립 커넥션에서 본 상태. */
    record Published(long messageId, boolean transactionActive, String statusInSameTransaction,
            String statusFromOtherConnection) {
    }

    static final List<Published> PUBLISHED = new CopyOnWriteArrayList<>();
    static final AtomicBoolean FAIL_ON_PUBLISH = new AtomicBoolean(false);

    /** findPlanRun이 불린 순간: 오래된 행의 상태, 그 회원의 EXPLANATION·PENDING 행 수. */
    record Observed(String staleStatus, long pendingExplanations) {
    }

    private final List<Observed> observed = new CopyOnWriteArrayList<>();

    @MockitoSpyBean
    PlanContextProvider planContextProvider;

    @MockitoSpyBean
    CalcEngine calcEngine;

    @MockitoSpyBean
    CurrentPlanResultService currentPlanResultService;

    @BeforeEach
    void stubPortAndClear() {
        observed.clear();
        PUBLISHED.clear();
        FAIL_ON_PUBLISH.set(false);
        clearInvocations(planContextProvider, calcEngine, currentPlanResultService);
        given(planContextPort.findForCalcRun(anyLong(), anyLong(), any())).willAnswer(invocation -> Optional.of(
                new PlanContextSnapshot(invocation.getArgument(2), JSON.readTree("{}"))));
    }

    @AfterEach
    void stopFailing() {
        FAIL_ON_PUBLISH.set(false);
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

    private long pendingExplanations(long member) {
        return jdbc.sql("SELECT count(*) FROM chat_message WHERE member_id = ? AND message_type = 'EXPLANATION' "
                + "AND status = 'PENDING'").param(member).query(Long.class).single();
    }

    private long calcRuns() {
        return jdbc.sql("SELECT count(*) FROM calc_run").query(Long.class).single();
    }

    /**
     * F-3 요청 처리는 findPlanRun으로 소유권·PLAN 여부·기준 시나리오만 확인하고 재계산하지 않는다(G-9b, C1a-10).
     * 계산 엔진·결과 조회 서비스·planContext 생성 메서드가 불리지 않고 calc_run도 새로 기록되지 않는다.
     * 리스너 쪽 planContext 포트는 스텁이라 요청 뒤 비동기 처리도 calc를 부르지 않는다.
     */
    @Test
    void explanationRequestDoesNotRecalculate() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long calcRunsBefore = calcRuns();
        clearInvocations(planContextProvider, calcEngine, currentPlanResultService);

        mockMvc.perform(post("/api/chat/explanations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isAccepted());

        assertThat(pendingExplanations(memberId)).isEqualTo(1);
        verify(planContextProvider, times(1)).findPlanRun(memberId, calcRun);
        verify(planContextProvider, never()).findForCalcRun(anyLong(), anyLong(), any());
        verify(planContextProvider, never()).findForCurrentPlan(anyLong());
        verifyNoInteractions(calcEngine, currentPlanResultService);
        assertThat(calcRuns()).as("요청 처리 중 calc_run을 새로 기록하지 않는다").isEqualTo(calcRunsBefore);
    }

    @Test
    void stalePendingIsFailedBeforeFindPlanRunAndRowIsSavedAfter() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        willAnswer(invocation -> {
            observed.add(new Observed(statusOf(staleAnswer), pendingExplanations(memberId)));
            return invocation.callRealMethod();
        }).given(planContextProvider).findPlanRun(anyLong(), anyLong());

        mockMvc.perform(post("/api/chat/explanations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isAccepted());

        verify(planContextProvider, times(1)).findPlanRun(memberId, calcRun);
        assertThat(observed).containsExactly(new Observed("FAILED", 0));
        assertThat(pendingExplanations(memberId)).isEqualTo(1);
        assertThat(statusOf(staleAnswer)).isEqualTo("FAILED");
    }

    /**
     * 기준 시나리오는 F-3의 findPlanRun 결과로 정해진다(G-9b). findPlanRun이 GOOD을 돌려준 직후, 리스너가 처음 처리하기 전에
     * 현재 계획을 BAD로 바꾼다(F-3 커밋보다 먼저 반영되므로 리스너는 처음부터 BAD인 현재 계획을 본다).
     * 최초 EXPLAIN도 저장 값 GOOD을 써야 한다: 포트 인자, 요청 baselineScenario, planContext.result.baselineScenario.
     */
    @Test
    void firstExplainUsesBaselineStoredAtRequestEvenIfPlanChangedBeforeListener() throws Exception {
        long calcRun = planRunOf(memberId, "GOOD");
        given(planContextPort.findForCalcRun(anyLong(), anyLong(), any())).willAnswer(invocation -> {
            long requestedRun = invocation.getArgument(1);
            Scenario baseline = invocation.getArgument(2);
            return Optional.of(new PlanContextSnapshot(baseline, JSON.readTree(
                    "{\"calcRunId\":%d,\"result\":{\"baselineScenario\":\"%s\"}}".formatted(
                            requestedRun, baseline.name()))));
        });
        AtomicBoolean changed = new AtomicBoolean(false);
        willAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            if (changed.compareAndSet(false, true)) {
                jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD' WHERE member_id = ?").param(memberId).update();
            }
            return found;
        }).given(planContextProvider).findPlanRun(anyLong(), anyLong());

        mockMvc.perform(post("/api/chat/explanations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isAccepted());

        assertThat(changed).as("findPlanRun 뒤에 계획을 바꿨다").isTrue();
        assertThat(jdbc.sql("SELECT baseline_scenario FROM plan WHERE member_id = ?").param(memberId)
                .query(String.class).single()).isEqualTo("BAD");
        long explanationId = jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? AND calc_run_id = ? "
                + "AND message_type = 'EXPLANATION'").params(memberId, calcRun).query(Long.class).single();
        JsonNode first = awaitAiRequests(1).get(0);

        assertThat(first.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(first.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(first.get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(first.get("planContext").get("result").get("baselineScenario").asString()).isEqualTo("GOOD");
        verify(planContextPort, times(1)).findForCalcRun(memberId, calcRun, Scenario.GOOD);
        verify(planContextPort, never()).findForCalcRun(anyLong(), anyLong(), eq(Scenario.BAD));
        assertThat(jdbc.sql("SELECT baseline_scenario FROM chat_message WHERE id = ?").param(explanationId)
                .query(String.class).single()).isEqualTo("GOOD");
        assertThat(AI.requests()).hasSize(1);
    }

    /**
     * 설명 행 저장과 ChatAnswerRequested 발행은 한 트랜잭션이다(modulith.md "발행은 트랜잭션 안에서", H-4 저장 → 발행).
     * 발행 순간 트랜잭션이 열려 있고, 그 트랜잭션에서는 대기 행이 보이지만 독립 커넥션에서는 아직 보이지 않는다(미커밋).
     */
    @Test
    void explanationRowAndAnswerRequestedArePublishedInOneTransaction() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");

        mockMvc.perform(post("/api/chat/explanations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isAccepted());

        long saved = jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? AND calc_run_id = ? "
                        + "AND message_type = 'EXPLANATION'").params(memberId, calcRun).query(Long.class).single();
        assertThat(PUBLISHED).containsExactly(new Published(saved, true, "PENDING", "NONE"));
    }

    /**
     * 발행이 실패하면 같은 트랜잭션의 설명 행도 함께 롤백된다. 대기 행만 남아 ai-server 호출 없이 갇히지 않는다.
     * 앞서 한 오래된 PENDING 정리도 같은 트랜잭션이라 함께 롤백된다. 롤백하지 않는 것은 404뿐이다(결정 U-3, F-1과 같은 방식).
     */
    @Test
    void failureWhilePublishingRollsBackTheExplanationRow() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        FAIL_ON_PUBLISH.set(true);
        try {
            mockMvc.perform(post("/api/chat/explanations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"calcRunId\":" + calcRun + "}")).andReturn();
        } catch (Exception e) {
            // 실패 주입이라 응답 모양은 보지 않는다. 남은 데이터만 본다
        } finally {
            FAIL_ON_PUBLISH.set(false);
        }

        assertThat(PUBLISHED).as("저장 뒤 발행까지 갔다").hasSize(1);
        assertThat(countRows(memberId)).as("발행 실패면 설명 행도 롤백되고 오래된 행만 남는다").isEqualTo(1);
        assertThat(pendingExplanations(memberId)).isZero();
        assertThat(jdbc.sql("SELECT status, message_type, content FROM chat_message WHERE id = ?").param(staleAnswer)
                .query().singleRow()).as("정리도 함께 롤백되어 요청 전과 같다")
                .containsEntry("status", "PENDING").containsEntry("message_type", "ANSWER")
                .containsEntry("content", null);
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 비동기 처리에 넘기기 전(발행 중) 실패한 F-3의 응답은 500 INTERNAL_ERROR, problem+json, errors=[]이고
     * 내부 예외 메시지를 싣지 않는다(C12b-9, C3-1). 롤백 뒤에도 202를 돌려주는 구현은 여기서 실패한다.
     */
    @Test
    void failureWhilePublishingReturns500InternalErrorWithoutInternalMessage() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        FAIL_ON_PUBLISH.set(true);
        MvcResult result;
        try {
            result = mockMvc.perform(post("/api/chat/explanations").cookie(cookie)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"calcRunId\":" + calcRun + "}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors").isEmpty())
                    .andReturn();
        } finally {
            FAIL_ON_PUBLISH.set(false);
        }

        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("발행 실패 주입").doesNotContain("IllegalStateException");
        // 인증이 통과한 요청의 5xx에도 토큰을 연장한다(frontend-api.md C12a-2-2)
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).as("500에도 새 토큰 쿠키")
                .anySatisfy(value -> assertThat(value).startsWith(JwtTokenService.COOKIE_NAME + "=")
                        .doesNotStartWith(JwtTokenService.COOKIE_NAME + "=;"));
        assertThat(PUBLISHED).as("저장 뒤 발행까지 갔다").hasSize(1);
        assertThat(countRows(memberId)).as("발행 실패면 설명 행도 롤백된다").isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublishObserverConfig {

        @Bean
        PublishObserver publishObserver(JdbcClient jdbc, TransactionTemplate transactions) {
            return new PublishObserver(jdbc, transactions);
        }
    }

    /** ChatAnswerRequested를 발행 스레드에서 동기로 듣고 그 순간을 기록한다. 실패 주입이 켜져 있으면 예외를 던진다. */
    static final class PublishObserver {

        private final JdbcClient jdbc;
        private final TransactionTemplate independent;

        PublishObserver(JdbcClient jdbc, TransactionTemplate transactions) {
            this.jdbc = jdbc;
            this.independent = new TransactionTemplate(transactions.getTransactionManager());
            this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @EventListener
        void record(ChatAnswerRequested event) {
            long id = event.assistantMessageId();
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            String same = statusOf(id);
            String other = independent.execute(status -> statusOf(id));
            PUBLISHED.add(new Published(id, active, same, other));
            if (FAIL_ON_PUBLISH.get()) {
                throw new IllegalStateException("발행 실패 주입");
            }
        }

        private String statusOf(long id) {
            return jdbc.sql("SELECT status FROM chat_message WHERE id = ?").param(id).query(String.class)
                    .optional().orElse("NONE");
        }
    }
}
