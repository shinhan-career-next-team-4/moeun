package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.moeun.backend.chat.application.StalePendingCleaner;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.rag.Citation;
import com.moeun.backend.rag.CitationQueryService;

import tools.jackson.databind.JsonNode;

/**
 * F-2가 무엇을 어떤 트랜잭션에서 부르는지 (#42, C12b-5·C12b-11-3, 사용자 결정 "rag 모듈 서비스"·B1).
 * - answerPending은 F-1 ANSWER_PENDING과 같은 메서드(ChatMessageRepository.existsPendingAnswer)로 판정한다.
 * - 오래된 PENDING 정리와 조회는 같은, 읽기 전용이 아닌 트랜잭션 안에서 정리 → 조회 순서로 한다.
 * - 출처는 rag 공개 서비스에 메시지 id 목록을 한 번 넘겨 받는다.
 */
class ChatPollingCollaborationTest extends ChatIntegrationTestSupport {

    /** 호출 시점의 트랜잭션 상태: 이름(같은 트랜잭션인지), 실제 트랜잭션 여부, 읽기 전용 여부. */
    record TxState(String call, String name, boolean active, boolean readOnly) {
    }

    static final List<TxState> TX_STATES = new CopyOnWriteArrayList<>();

    @MockitoSpyBean
    ChatMessageRepository messageRepository;

    @MockitoSpyBean
    StalePendingCleaner cleaner;

    @MockitoSpyBean
    CitationQueryService citationQueryService;

    @BeforeEach
    void recordTransactions() {
        TX_STATES.clear();
        clearInvocations(messageRepository, cleaner, citationQueryService);
        doAnswer(invocation -> {
            TX_STATES.add(current("failStale"));
            return invocation.callRealMethod();
        }).when(cleaner).failStale(anyLong());
        doAnswer(invocation -> {
            TX_STATES.add(current("existsPendingAnswer"));
            return invocation.callRealMethod();
        }).when(messageRepository).existsPendingAnswer(anyLong());
    }

    static TxState current(String call) {
        return new TxState(call, TransactionSynchronizationManager.getCurrentTransactionName(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    JsonNode poll(Long afterId) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/chat/messages").cookie(cookie);
        if (afterId != null) {
            request.param("afterId", String.valueOf(afterId));
        }
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode message : body.get("messages")) {
            ids.add(message.get("id").asLong());
        }
        return ids;
    }

    long citedAnswer(String content, long chunkId) {
        long id = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", content);
        jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) VALUES (?, 1, ?)")
                .params(id, chunkId).update();
        return id;
    }

    // ---------- AC-8 answerPending은 F-1과 같은 메서드 ----------

    @Test
    void answerPendingUsesTheSameMethodAsQuestionGuard() throws Exception {
        insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        for (Long afterId : new Long[] {null, 0L}) {
            clearInvocations(messageRepository);
            JsonNode body = poll(afterId);
            assertThat(body.get("answerPending").asBoolean()).isTrue();
            verify(messageRepository, atLeastOnce()).existsPendingAnswer(memberId);
            verify(messageRepository, never()).existsPendingAnswer(otherMemberId);
        }
    }

    /** answerPending은 그 메서드의 반환값 자체다. 호출만 하고 결과를 버린 채 따로 계산하면 안 된다. */
    @Test
    void answerPendingFollowsTheSharedMethodResult() throws Exception {
        long pending = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        doReturn(false).when(messageRepository).existsPendingAnswer(memberId);
        for (Long afterId : new Long[] {null, 0L}) {
            assertThat(poll(afterId).get("answerPending").asBoolean())
                    .as("실제 ANSWER PENDING이 있어도 메서드가 false면 false").isFalse();
        }

        jdbc.sql("UPDATE chat_message SET status = 'COMPLETED', content = '답변' WHERE id = ?").param(pending).update();
        doReturn(true).when(messageRepository).existsPendingAnswer(memberId);
        for (Long afterId : new Long[] {null, 0L}) {
            assertThat(poll(afterId).get("answerPending").asBoolean())
                    .as("PENDING이 없어도 메서드가 true면 true").isTrue();
        }
    }

    // ---------- AC-13 정리 → 조회가 같은, 읽기 전용이 아닌 트랜잭션 ----------

    @Test
    void cleanupAndQueryRunInOneWritableTransactionInThatOrder() throws Exception {
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");

        for (Long afterId : new Long[] {null, 0L}) {
            TX_STATES.clear();
            clearInvocations(cleaner, messageRepository);
            poll(afterId);

            InOrder order = inOrder(cleaner, messageRepository);
            order.verify(cleaner).failStale(memberId);
            order.verify(messageRepository).existsPendingAnswer(memberId);

            assertThat(TX_STATES).extracting(TxState::call).contains("failStale", "existsPendingAnswer");
            assertThat(TX_STATES).allSatisfy(state -> {
                assertThat(state.active()).as(state.call() + " 실제 트랜잭션 안").isTrue();
                assertThat(state.readOnly()).as(state.call() + " 읽기 전용 아님").isFalse();
            });
            assertThat(TX_STATES).extracting(TxState::name).as("같은 트랜잭션").containsOnly(TX_STATES.get(0).name());
        }
    }

    /**
     * 정리 UPDATE와 조회가 한 트랜잭션이면, 정리 뒤 조회가 실패할 때 정리도 함께 되돌아간다.
     * 정리를 따로 먼저 커밋하는 구현은 실패 뒤에도 행이 FAILED로 남는다.
     */
    @Test
    void failureAfterCleanupRollsBackTheCleanup() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        doThrow(new IllegalStateException("조회 실패 주입")).when(messageRepository).existsPendingAnswer(memberId);

        for (Long afterId : new Long[] {null, 0L}) {
            clearInvocations(cleaner);
            MockHttpServletRequestBuilder request = get("/api/chat/messages").cookie(cookie);
            if (afterId != null) {
                request.param("afterId", String.valueOf(afterId));
            }
            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();

            verify(cleaner).failStale(memberId);
            assertThat(status / 100).as("조회 실패는 성공(2xx) 응답이 아니다: " + status).isNotEqualTo(2);
            Map<String, Object> row = jdbc.sql("SELECT status, message_type, content FROM chat_message WHERE id = ?")
                    .param(stale).query().singleRow();
            assertThat(row.get("status")).as("정리 UPDATE가 조회 실패와 함께 롤백").isEqualTo("PENDING");
            assertThat(row.get("message_type")).isEqualTo("ANSWER");
            assertThat(row.get("content")).isNull();
        }
    }

    /**
     * 출처 조회(rag 공개 서비스)도 정리와 같은 트랜잭션 안이다. 정리 뒤 출처 조회가 실패하면 응답은 성공이 아니고
     * 정리 UPDATE도 롤백된다. 정리·플래그만 묶어 먼저 커밋하고 출처를 그 밖에서 읽는 구현은 여기서 걸린다.
     */
    @Test
    void citationLookupFailureAfterCleanupRollsBackTheCleanup() throws Exception {
        List<Long> chunks = insertChunks();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        citedAnswer("출처가 있는 답변", chunks.get(0));
        doThrow(new IllegalStateException("출처 조회 실패 주입"))
                .when(citationQueryService).findByMessageIds(anyCollection());

        for (Long afterId : new Long[] {null, question}) {
            clearInvocations(cleaner, messageRepository, citationQueryService);
            MockHttpServletRequestBuilder request = get("/api/chat/messages").cookie(cookie);
            if (afterId != null) {
                request.param("afterId", String.valueOf(afterId));
            }
            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();

            InOrder order = inOrder(cleaner, citationQueryService);
            order.verify(cleaner).failStale(memberId);
            order.verify(citationQueryService).findByMessageIds(anyCollection());
            assertThat(status / 100).as("출처 조회 실패는 성공(2xx) 응답이 아니다: " + status).isNotEqualTo(2);
            Map<String, Object> row = jdbc.sql("SELECT status, message_type, content FROM chat_message WHERE id = ?")
                    .param(stale).query().singleRow();
            assertThat(row.get("status")).as("정리 UPDATE가 출처 조회 실패와 함께 롤백").isEqualTo("PENDING");
            assertThat(row.get("message_type")).isEqualTo("ANSWER");
            assertThat(row.get("content")).isNull();
        }
    }

    // ---------- AC-15 출처는 rag 서비스에 메시지 id 목록을 한 번 넘겨 받는다 ----------

    @Test
    @SuppressWarnings("unchecked")
    void citationsComeFromRagServiceCalledOnceWithMessageIds() throws Exception {
        List<Long> chunks = insertChunks();
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long first = citedAnswer("첫 답변", chunks.get(0));
        long second = citedAnswer("둘째 답변", chunks.get(1));
        long others = insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "COMPLETED", "남의 답변");

        for (Long afterId : new Long[] {null, question}) {
            clearInvocations(citationQueryService);
            JsonNode body = poll(afterId);

            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(citationQueryService, times(1)).findByMessageIds(captor.capture());
            Collection<Long> requested = captor.getValue();
            assertThat(requested).contains(first, second).doesNotContain(others);
            assertThat(ids(body)).containsAll(requested);
            for (JsonNode message : body.get("messages")) {
                long id = message.get("id").asLong();
                if (id == first || id == second) {
                    assertThat(message.get("citations").size()).isEqualTo(1);
                    assertThat(message.get("citations").get(0).get("title").asString()).isEqualTo("chat test");
                    assertThat(message.get("citations").get(0).get("orgName").asString())
                            .isEqualTo("chat-test-org");
                }
            }
        }
    }

    /** rag 서비스가 준 출처를 그대로(순서 포함) 싣는다. chat이 출처를 따로 만들지 않는다. */
    @Test
    void ragServiceResultIsWhatTheResponseShows() throws Exception {
        long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변");
        doAnswer(invocation -> Map.of(answer, List.of(
                new Citation("둘째 문서", "https://example.com/2", "기관 2"),
                new Citation("첫 문서", "https://example.com/1", "기관 1"))))
                .when(citationQueryService).findByMessageIds(anyCollection());

        JsonNode citations = poll(null).get("messages").get(0).get("citations");

        verify(citationQueryService, times(1)).findByMessageIds(anyCollection());
        assertThat(citations.size()).isEqualTo(2);
        assertThat(citations.get(0).get("title").asString()).isEqualTo("둘째 문서");
        assertThat(citations.get(0).get("sourceUrl").asString()).isEqualTo("https://example.com/2");
        assertThat(citations.get(0).get("orgName").asString()).isEqualTo("기관 2");
        assertThat(citations.get(1).get("title").asString()).isEqualTo("첫 문서");
    }
}
