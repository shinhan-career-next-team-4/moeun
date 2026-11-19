package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * ai-server 시간 초과·연결 실패 (#41 AC-3, 계약 C12b-11-1·C12b-11-2).
 * 응답 타임아웃을 1초로 줄인 별도 컨텍스트에서 돈다(30초를 기다리지 않기 위해). 연결 타임아웃은 설정 그대로 2초다.
 */
@TestPropertySource(properties = "moeun.ai-server.read-timeout=1s")
class ChatAiServerTimeoutTest extends ChatIntegrationTestSupport {

    private void assertFailed(long id) {
        Map<String, Object> failed = row(id);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());
    }

    @Test
    void responseTimeoutMarksFailed() throws Exception {
        AI.respondAfter(Duration.ofSeconds(4), 200, AiServerStub.ANSWER_OK);

        long assistantId = askAndGetAssistantId(cookie, "오래 걸리는 질문");
        awaitStatus(assistantId, "FAILED");

        assertFailed(assistantId);
    }

    /**
     * 실제 호출이 설정한 응답 타임아웃(1초)을 쓰는지 구별한다: 1.7초 뒤 오는 정상 답은 1초 타임아웃이면 FAILED,
     * 2초 이상(예: 별도 팩토리의 다른 값·기본 30초)이면 COMPLETED가 된다. 또 그 호출이 aiServerRequestFactory 빈을
     * 거쳤는지 확인한다(빈의 연결 타임아웃 2초가 실제 호출에 쓰인다는 근거).
     */
    @Test
    void realCallUsesConfiguredReadTimeoutThroughFactoryBean() throws Exception {
        AI.respondAfter(Duration.ofMillis(1700), 200, AiServerStub.ANSWER_OK);

        long assistantId = askAndGetAssistantId(cookie, "1.7초 뒤 답이 오는 질문");
        awaitStatus(assistantId, "FAILED");

        assertFailed(assistantId);
        assertThat(AI.requests()).hasSize(1);
        assertThat(aiCallTransactionStates()).isNotEmpty();
        assertThat(connectTimeoutOfAiClient()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void connectionFailureMarksFailed() throws Exception {
        AI.goDown();
        try {
            long assistantId = askAndGetAssistantId(cookie, "ai-server가 꺼져 있을 때 질문");
            awaitStatus(assistantId, "FAILED");

            assertFailed(assistantId);
            assertThat(aiCallTransactionStates()).isNotEmpty();
        } finally {
            AI.comeUp();
        }
    }

    @Test
    void requestFactoryUsesConfiguredTimeouts() {
        assertThat(connectTimeoutOfAiClient()).isEqualTo(Duration.ofSeconds(2));
        assertThat(readTimeoutOfAiClient()).isEqualTo(Duration.ofSeconds(1));
    }
}
