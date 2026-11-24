package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.moeun.backend.chat.domain.ChatMessage;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.MessageTransition;
import com.moeun.backend.chat.domain.MessageType;

/**
 * 메시지 상태 전이의 동시성(#312). 답변 완료·실패는 PENDING일 때만 바뀌고, 그 사이 로그아웃 삭제나 오래된 PENDING 정리가
 * 먼저 끝났으면 0행으로 끝나 무시된다. 삭제된 행을 되살리지 않고, 정리된(FAILED) 행을 완료로 덮지 않는다.
 * <p>답변 완료는 리스너처럼 PENDING 메시지를 먼저 읽어 두고(ai-server 호출 전), 나중에 그 메시지의 전이를 저장한다.
 * <p>"동시" 경우는 한 트랜잭션이 행 잠금을 쥔 채 커밋을 미루는 동안 다른 트랜잭션이 그 행을 기다리게 해서 재현한다.
 */
class ChatMessageConcurrencyTest extends ChatIntegrationTestSupport {

    private static final String FAILED_CONTENT = "답변을 만들지 못했어요";
    private static final OffsetDateTime FUTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

    @Autowired
    ChatMessageRepository messages;

    /** PENDING 답변 행을 넣고, 리스너처럼 호출 전에 읽어 둔다. */
    private ChatMessage pendingAnswer() {
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long id = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);
        return messages.findPending(id, memberId).orElseThrow();
    }

    private int complete(ChatMessage message) {
        return messages.apply(message.complete(MessageType.ANSWER, "답변", null, "AVG", null));
    }

    private int cleanUp() {
        return messages.failPendingCreatedBefore(memberId, FUTURE, FAILED_CONTENT);
    }

    private int delete() {
        return messages.deleteAllOf(memberId);
    }

    private String contentOf(long id) {
        return jdbc.sql("SELECT content FROM chat_message WHERE id = ?").param(id).query(String.class).single();
    }

    // ---------- 순서대로 ----------

    @Test
    @DisplayName("로그아웃 삭제 뒤에 온 답변 완료는 0행이고 행을 되살리지 않는다")
    void completionAfterDeletionIsIgnored() {
        ChatMessage message = pendingAnswer();
        delete();

        assertThat(complete(message)).isZero();
        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("오래된 PENDING 정리 뒤에 온 답변 완료는 0행이고 FAILED를 덮지 않는다")
    void completionAfterCleanupIsIgnored() {
        ChatMessage message = pendingAnswer();
        long id = message.id();
        assertThat(cleanUp()).isEqualTo(1);

        assertThat(complete(message)).isZero();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(contentOf(id)).isEqualTo(FAILED_CONTENT);
    }

    @Test
    @DisplayName("답변 완료 뒤의 정리와 실패 처리는 0행이고 COMPLETED를 바꾸지 않는다")
    void cleanupAndFailAfterCompletionAreIgnored() {
        ChatMessage message = pendingAnswer();
        long id = message.id();
        assertThat(complete(message)).isEqualTo(1);

        assertThat(cleanUp()).isZero();
        assertThat(messages.apply(message.fail(FAILED_CONTENT))).isZero();
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(contentOf(id)).isEqualTo("답변");
    }

    @Test
    @DisplayName("오래된 PENDING 일괄 정리는 행마다 ChatMessage.fail과 같은 전이다(계산 기록·기준 시나리오를 남긴다)")
    void bulkCleanupMatchesChatMessageFail() {
        long calcRunId = insertCalcRun(memberId, "PLAN");
        long id = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, calc_run_id, "
                        + "baseline_scenario) VALUES (?, 'ASSISTANT', 'EXPLANATION', 'PENDING', ?, 'GOOD') "
                        + "RETURNING id")
                .params(memberId, calcRunId).query(Long.class).single();
        MessageTransition expected = messages.findPending(id, memberId).orElseThrow().fail(FAILED_CONTENT);

        assertThat(cleanUp()).isEqualTo(1);

        StoredRow stored = jdbc.sql("SELECT status, message_type, content, calc_run_id, baseline_scenario, "
                        + "condition_changes::text AS changes FROM chat_message WHERE id = ?")
                .param(id)
                .query((rs, row) -> new StoredRow(rs.getString("status"), rs.getString("message_type"),
                        rs.getString("content"), rs.getObject("calc_run_id", Long.class),
                        rs.getString("baseline_scenario"), rs.getString("changes")))
                .single();
        assertThat(stored).isEqualTo(new StoredRow(expected.status().name(), expected.type().name(),
                expected.content(), expected.calcRunId(), expected.baselineScenario(),
                expected.conditionChangesJson()));
    }

    // ---------- 동시(행 잠금 대기) ----------

    @Test
    @DisplayName("답변 완료가 잠금을 쥔 동안 기다리던 로그아웃 삭제는 완료 커밋 뒤 그 행을 지운다")
    void deletionWaitingOnCompletionStillDeletes() throws Exception {
        ChatMessage message = pendingAnswer();

        Outcome outcome = whileFirstHoldsLock(() -> complete(message), this::delete);

        assertThat(outcome.first()).isEqualTo(1);
        assertThat(outcome.second()).isEqualTo(2);
        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("로그아웃 삭제가 잠금을 쥔 동안 기다리던 답변 완료는 0행이고 행을 되살리지 않는다")
    void completionWaitingOnDeletionIsIgnored() throws Exception {
        ChatMessage message = pendingAnswer();

        Outcome outcome = whileFirstHoldsLock(this::delete, () -> complete(message));

        assertThat(outcome.second()).isZero();
        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("정리가 잠금을 쥔 동안 기다리던 답변 완료는 0행이고 FAILED로 남는다")
    void completionWaitingOnCleanupIsIgnored() throws Exception {
        ChatMessage message = pendingAnswer();
        long id = message.id();

        Outcome outcome = whileFirstHoldsLock(this::cleanUp, () -> complete(message));

        assertThat(outcome.first()).isEqualTo(1);
        assertThat(outcome.second()).isZero();
        assertThat(statusOf(id)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("답변 완료가 잠금을 쥔 동안 기다리던 정리는 0행이고 COMPLETED로 남는다")
    void cleanupWaitingOnCompletionIsIgnored() throws Exception {
        ChatMessage message = pendingAnswer();
        long id = message.id();

        Outcome outcome = whileFirstHoldsLock(() -> complete(message), this::cleanUp);

        assertThat(outcome.first()).isEqualTo(1);
        assertThat(outcome.second()).isZero();
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }

    /**
     * first를 트랜잭션 안에서 실행하고 커밋을 미룬다. 그동안 second를 다른 트랜잭션으로 시작해 행 잠금을 기다리게 한 뒤
     * first를 커밋한다. 두 작업이 바꾼 행 수를 돌려준다.
     */
    private Outcome whileFirstHoldsLock(IntSupplier first, IntSupplier second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> firstDone = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            int rows = first.getAsInt();
            locked.countDown();
            await(release);
            return rows;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> secondDone = CompletableFuture.supplyAsync(
                () -> transactions.execute(status -> second.getAsInt()));
        waitUntilSomeoneWaitsOnLock();
        release.countDown();
        return new Outcome(firstDone.get(10, TimeUnit.SECONDS), secondDone.get(10, TimeUnit.SECONDS));
    }

    private void waitUntilSomeoneWaitsOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 번째 트랜잭션이 행 잠금을 기다리지 않았다");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("잠금 해제 신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Outcome(int first, int second) {
    }

    private record StoredRow(String status, String type, String content, Long calcRunId, String baselineScenario,
            String conditionChanges) {
    }
}
