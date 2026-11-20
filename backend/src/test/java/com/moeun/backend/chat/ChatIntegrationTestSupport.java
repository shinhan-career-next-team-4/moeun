package com.moeun.backend.chat;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.chat.application.ChatAnswerRequested;
import com.moeun.backend.chat.application.PlanContextPort;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.config.copy.CopyBook;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * chat 통합 테스트 공통 기반(#41). 실제 Postgres + 스텁 ai-server + 고정 가능한 시계 + PlanContextPort 스텁.
 * 하위 클래스가 같은 설정을 쓰면 스프링 컨텍스트를 공유한다.
 */
@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456,demo02:pw-123456")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, ChatIntegrationTestSupport.ClockTestConfig.class})
abstract class ChatIntegrationTestSupport {

    static final Duration WAIT = Duration.ofSeconds(15);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AiServerStub AI = AiServerStub.start();
    static final List<Boolean> AI_CALL_TRANSACTION_STATES = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("moeun.ai-server.base-url", AI::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CopyBook copy;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactions;

    @MockitoBean
    PlanContextPort planContextPort;

    Cookie cookie;
    long memberId;
    Cookie otherCookie;
    long otherMemberId;

    @BeforeEach
    void prepare() throws Exception {
        AI.reset();
        AI_CALL_TRANSACTION_STATES.clear();
        clock.useSystem();
        jdbc.sql("DELETE FROM chat_message").update();
        jdbc.sql("DELETE FROM event_publication").update();
        cookie = login("demo01");
        memberId = memberId("demo01");
        otherCookie = login("demo02");
        otherMemberId = memberId("demo02");
        jdbc.sql("DELETE FROM chat_message").update();
    }

    @AfterEach
    void settle() {
        AI.release(500, "{\"error\":\"TEST_END\"}");
        AI.awaitIdle();
        clock.useSystem();
    }

    Cookie login(String loginId) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    long memberId(String loginId) {
        return jdbc.sql("SELECT id FROM member WHERE login_id = ?").param(loginId).query(Long.class).single();
    }

    ResultActions ask(Cookie who, String body) throws Exception {
        return mockMvc.perform(post("/api/chat/messages").cookie(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions askText(Cookie who, String text) throws Exception {
        return ask(who, JSON.writeValueAsString(Map.of("text", text)));
    }

    /** 질문을 보내고 202 본문의 assistantMessageId를 돌려준다. */
    long askAndGetAssistantId(Cookie who, String text) throws Exception {
        String body = askText(who, text).andReturn().getResponse().getContentAsString();
        JsonNode node = JSON.readTree(body);
        if (!node.has("assistantMessageId")) {
            throw new AssertionError("질문이 받아들여지지 않았어요: " + body);
        }
        return node.get("assistantMessageId").asLong();
    }

    String failedSentence() {
        return copy.get("chat.failed");
    }

    // ---------- DB 관찰 ----------

    Map<String, Object> row(long id) {
        return jdbc.sql("SELECT id, member_id, role, message_type, status, content, calc_run_id, baseline_scenario, "
                        + "condition_changes::text AS condition_changes FROM chat_message WHERE id = ?")
                .param(id).query().singleRow();
    }

    List<Map<String, Object>> rowsOf(long member) {
        return jdbc.sql("SELECT id, role, message_type, status, content FROM chat_message WHERE member_id = ? "
                + "ORDER BY id").param(member).query().listOfRows();
    }

    long countRows(long member) {
        return jdbc.sql("SELECT count(*) FROM chat_message WHERE member_id = ?").param(member)
                .query(Long.class).single();
    }

    String statusOf(long id) {
        return jdbc.sql("SELECT status FROM chat_message WHERE id = ?").param(id).query(String.class)
                .optional().orElse("DELETED");
    }

    void awaitStatus(long id, String expected) {
        await().atMost(WAIT).until(() -> expected.equals(statusOf(id)));
    }

    List<JsonNode> awaitAiRequests(int count) {
        await().atMost(WAIT).until(() -> AI.requests().size() >= count);
        return AI.requests();
    }

    long answerEvents() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type LIKE '%ChatAnswerRequested'")
                .query(Long.class).single();
    }

    long incompleteAnswerEvents() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type LIKE '%ChatAnswerRequested' "
                + "AND completion_date IS NULL").query(Long.class).single();
    }

    void awaitAnswerEventsCompleted() {
        await().atMost(WAIT).until(() -> answerEvents() > 0 && incompleteAnswerEvents() == 0);
    }

    // ---------- 고정 데이터 ----------

    long insertMessage(long member, String role, String type, String status, String content) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(member, role, type, status, content).query(Long.class).single();
    }

    long insertMessageAt(long member, String role, String type, String status, String content, Instant createdAt) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
                .params(member, role, type, status, content, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .query(Long.class).single();
    }

    long insertExplanation(long member, String status, String content, long calcRunId, String baseline,
            Instant createdAt) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, calc_run_id, "
                        + "baseline_scenario, created_at) VALUES (?, 'ASSISTANT', 'EXPLANATION', ?, ?, ?, ?, ?) "
                        + "RETURNING id")
                .params(member, status, content, calcRunId, baseline,
                        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .query(Long.class).single();
    }

    /** chat_message.calc_run_id가 참조할 calc_run 한 행(plan_version·data_version 포함). */
    long insertCalcRun(long member, String runType) {
        long dataVersion = jdbc.sql("INSERT INTO data_version (data_kind, version_label, base_date, source) "
                        + "VALUES ('CALC_PARAM', 'chat-test', DATE '2026-11-01', 'chat test') "
                        + "ON CONFLICT ON CONSTRAINT uq_data_version_label DO UPDATE SET source = EXCLUDED.source "
                        + "RETURNING id")
                .query(Long.class).single();
        long planVersion = jdbc.sql("INSERT INTO plan_version (member_id, goal_type_code, goal_amount, "
                        + "target_period_months, calc_start_month, monthly_saving_amount, source) "
                        + "VALUES (?, 'HOUSE', 100000000, 36, DATE '2026-11-01', 1000000, 'INPUT') RETURNING id")
                .param(member).query(Long.class).single();
        return jdbc.sql("INSERT INTO calc_run (plan_version_id, run_type, calc_param_version_id, engine_version) "
                        + "VALUES (?, ?, ?, 'chat-test') RETURNING id")
                .params(planVersion, runType, dataVersion).query(Long.class).single();
    }

    /** citation이 참조할 rag_chunk 두 행. */
    List<Long> insertChunks() {
        long org = jdbc.sql("INSERT INTO source_org (name, org_type) VALUES ('chat-test-org', 'EDU_PROVIDER') "
                        + "ON CONFLICT ON CONSTRAINT uq_source_org_name DO UPDATE SET org_type = EXCLUDED.org_type "
                        + "RETURNING id")
                .query(Long.class).single();
        long document = jdbc.sql("INSERT INTO rag_document (doc_type, source_org_id, title, source_url, "
                        + "content_sha256) VALUES ('EDU', ?, 'chat test', 'https://example.com/chat-test', ?) "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_document_rev DO UPDATE SET title = EXCLUDED.title "
                        + "RETURNING id")
                .params(org, "a".repeat(64)).query(Long.class).single();
        return List.of(chunk(document, 1), chunk(document, 2));
    }

    private long chunk(long document, int chunkNo) {
        return jdbc.sql("INSERT INTO rag_chunk (document_id, chunk_no, content) VALUES (?, ?, 'chunk') "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_chunk_doc_no DO UPDATE SET content = EXCLUDED.content "
                        + "RETURNING id")
                .params(document, chunkNo).query(Long.class).single();
    }

    /** 리스너를 거치지 않고 ChatAnswerRequested를 커밋과 함께 발행한다(F-3 경로·재처리 흉내). */
    void publishAnswerRequested(long assistantMessageId, long member) {
        transactions.executeWithoutResult(status ->
                publisher.publishEvent(new ChatAnswerRequested(assistantMessageId, member)));
    }

    // ---------- ai-server 호출용 HTTP 클라이언트 설정 관찰 ----------

    /** aiServerRequestFactory 빈의 연결 타임아웃. JDK·Simple 요청 팩토리를 읽는다. */
    Duration connectTimeoutOfAiClient() {
        Object factory = context.getBean("aiServerRequestFactory");
        Object httpClient = field(factory, "httpClient");
        if (httpClient instanceof HttpClient jdk) {
            return jdk.connectTimeout().orElseThrow(() -> new AssertionError("연결 타임아웃이 설정되지 않았어요"));
        }
        return asDuration(field(factory, "connectTimeout"));
    }

    /** aiServerRequestFactory 빈의 응답(읽기) 타임아웃. */
    Duration readTimeoutOfAiClient() {
        return asDuration(field(context.getBean("aiServerRequestFactory"), "readTimeout"));
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(target);
            } catch (NoSuchFieldException e) {
                // 상위 클래스에서 다시 찾는다
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    private static Duration asDuration(Object value) {
        if (value instanceof Duration duration) {
            return duration;
        }
        if (value instanceof Number millis) {
            return Duration.ofMillis(millis.longValue());
        }
        throw new AssertionError("요청 팩토리에서 타임아웃 값을 읽지 못했어요: " + value);
    }

    /** 테스트가 시각을 정할 수 있는 시계. 정하지 않으면 시스템 시계다. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> fixed = new AtomicReference<>();

        void fixAt(Instant instant) {
            fixed.set(instant);
        }

        void useSystem() {
            fixed.set(null);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = fixed.get();
            return value != null ? value : Instant.now();
        }
    }

    /**
     * aiServerRequestFactory로 실제 요청을 만들 때마다(createRequest) 그 스레드에 Spring 트랜잭션이 열려 있었는지.
     * 비어 있으면 실제 ai-server 호출이 이 빈을 거치지 않은 것이다.
     */
    List<Boolean> aiCallTransactionStates() {
        return List.copyOf(AI_CALL_TRANSACTION_STATES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** aiServerRequestFactory 빈을 같은 타입의 spy로 바꿔 createRequest 시점의 트랜잭션 상태를 기록한다. 필드 값은 그대로다. */
        @Bean
        static BeanPostProcessor aiServerRequestFactoryRecorder() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!"aiServerRequestFactory".equals(beanName)) {
                        return bean;
                    }
                    return Mockito.mock(bean.getClass(), Mockito.withSettings().spiedInstance(bean)
                            .defaultAnswer(invocation -> {
                                if ("createRequest".equals(invocation.getMethod().getName())) {
                                    AI_CALL_TRANSACTION_STATES.add(
                                            TransactionSynchronizationManager.isActualTransactionActive());
                                }
                                return invocation.callRealMethod();
                            }));
                }
            };
        }
    }
}
