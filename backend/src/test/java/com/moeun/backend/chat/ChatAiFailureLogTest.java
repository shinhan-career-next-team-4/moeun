package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * ai-server 호출 실패 WARN 로그 계약(#123, #41 후속, FR-AI-04, NFR 보안·로그).
 * 남기는 값은 messageId, memberId, 오류 분류, HTTP 상태 코드와 #316부터 원인 상세(ai-server 응답 본문 또는 예외 메시지)다.
 * 구조화된 필드(SLF4J key-value 또는 MDC)로 운반한다. 키 이름·분류 값·메시지 문장은 구현 재량이라 특정 표기에 의존하지 않는다.
 * 내부 키 같은 비밀값은 남기지 않는다. 검사 대상은 이 호출 실패와 관련된 WARN뿐이다. 응답 타임아웃을 1초로 줄인 컨텍스트에서 돈다.
 */
@TestPropertySource(properties = "moeun.ai-server.read-timeout=1s")
class ChatAiFailureLogTest extends ChatIntegrationTestSupport {

    private static final String MARKER = "LEAK-MARKER-7f3a9c";

    @Value("${moeun.ai-server.internal-api-key}")
    String internalKey;

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
    }

    /** 이 메시지의 ai-server 호출 실패와 관련된 WARN. messageId 값이 필드나 문장에 있거나 호출 예외가 붙은 것(로거 이름은 보지 않는다). */
    private List<ILoggingEvent> relatedWarns(long messageId) {
        String id = String.valueOf(messageId);
        List<ILoggingEvent> related = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            if (event.getLevel() != Level.WARN) {
                continue;
            }
            boolean hasId = values(event).contains(id) || event.getFormattedMessage().contains(id);
            if (hasId || hasAiCallException(event)) {
                related.add(event);
            }
        }
        return related;
    }

    private static List<String> values(ILoggingEvent event) {
        List<String> values = new ArrayList<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> values.add(String.valueOf(pair.value)));
        }
        values.addAll(event.getMDCPropertyMap().values());
        return values;
    }

    private static boolean hasAiCallException(ILoggingEvent event) {
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            String name = t.getClassName();
            if (name.contains("RestClient") || name.contains("AiServer") || name.contains("ResourceAccess")) {
                return true;
            }
        }
        return false;
    }

    private void assertFailedAndLogContract(long assistantId) {
        assertFailedAndLogContract(assistantId, false);
    }

    /** bodyInDetail이면 ai-server가 돌려준 본문(표식)이 원인 상세 필드로 남아야 한다(#316). */
    private void assertFailedAndLogContract(long assistantId, boolean bodyInDetail) {
        Map<String, Object> failed = row(assistantId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());

        List<ILoggingEvent> related = relatedWarns(assistantId);
        assertThat(related).as("호출 실패와 관련된 WARN이 있어야 한다").isNotEmpty();
        String id = String.valueOf(assistantId);
        for (ILoggingEvent event : related) {
            assertThat(event.getThrowableProxy()).as("예외를 붙이지 않는다").isNull();
            assertThat(event.getFormattedMessage()).as("문장에는 값을 넣지 않는다").doesNotContain(MARKER);
            // 구조화된 필드는 messageId, memberId, 오류 분류, 상태 코드, 원인 상세를 담는다
            assertThat(values(event)).as("구조화된 필드가 있어야 한다").isNotEmpty().hasSizeLessThanOrEqualTo(5);
            assertThat(values(event)).contains(id);
            assertThat(String.join(" ", values(event)) + event.getFormattedMessage())
                    .as("내부 키는 남기지 않는다").doesNotContain(internalKey);
        }
        if (bodyInDetail) {
            assertThat(related).as("ai-server 본문이 원인 상세로 남아야 한다")
                    .anyMatch(event -> values(event).stream().anyMatch(value -> value.contains(MARKER)));
        }
    }

    @ParameterizedTest(name = "{displayName} › {0}")
    @ValueSource(ints = {400, 422, 500, 502})
    void errorStatusBodyIsLoggedAsDetail(int status) throws Exception {
        AI.respond(status, "{\"detail\":[{\"msg\":\"" + MARKER + "\"}],\"error\":\"" + MARKER + "\"}");

        long assistantId = askAndGetAssistantId(cookie, "질문 " + status);
        awaitStatus(assistantId, "FAILED");

        assertFailedAndLogContract(assistantId, true);
    }

    @Test
    void badOutputBodyWithOkStatusIsLoggedAsDetail() throws Exception {
        AI.respond(200, "{\"error\":\"" + MARKER + "\"}");

        long assistantId = askAndGetAssistantId(cookie, "질문");
        awaitStatus(assistantId, "FAILED");

        assertFailedAndLogContract(assistantId, true);
    }

    @Test
    void responseTimeoutLogsOnlyAllowedFields() throws Exception {
        AI.respondAfter(Duration.ofSeconds(4), 200, "{\"error\":\"" + MARKER + "\"}");

        long assistantId = askAndGetAssistantId(cookie, "오래 걸리는 질문");
        awaitStatus(assistantId, "FAILED");

        assertFailedAndLogContract(assistantId);
    }

    @Test
    void connectionFailureLogsOnlyAllowedFields() throws Exception {
        AI.goDown();
        try {
            long assistantId = askAndGetAssistantId(cookie, "ai-server가 꺼져 있을 때 질문");
            awaitStatus(assistantId, "FAILED");

            assertFailedAndLogContract(assistantId);
        } finally {
            AI.comeUp();
        }
    }

    /** (예외) 같은 시간에 호출 실패와 무관한 WARN이 찍혀도 검사 대상이 아니다. 그 WARN은 표식과 임의 필드를 담아도 된다. */
    @Test
    void unrelatedWarnIsNotChecked() throws Exception {
        AI.respond(502, "{\"error\":\"LLM_UNAVAILABLE\"}");

        long assistantId = askAndGetAssistantId(cookie, "질문");
        LoggerFactory.getLogger("com.moeun.backend.unrelated").atWarn()
                .addKeyValue("note", "자유 형식 문장 " + MARKER).addKeyValue("a", 1).addKeyValue("b", 2)
                .addKeyValue("c", 3).addKeyValue("d", 4).log("무관한 경고 " + MARKER);
        awaitStatus(assistantId, "FAILED");

        assertThat(relatedWarns(assistantId)).noneMatch(e -> e.getFormattedMessage().contains(MARKER));
        assertFailedAndLogContract(assistantId);
    }
}
