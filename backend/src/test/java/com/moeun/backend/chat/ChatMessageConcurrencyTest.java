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

import com.moeun.backend.chat.domain.ChatMessageRepository;

/**
 * 메시지 상태 전이의 동시성(#312). 답변 완료·실패는 PENDING일 때만 바뀌고, 그 사이 로그아웃 삭제나 오래된 PENDING 정리가
 * 먼저 끝났으면 0행으로 끝나 무시된다. 삭제된 행을 되살리지 않고, 정리된(FAILED) 행을 완료로 덮지 않는다.
 * <p>"동시" 경우는 한 트랜잭션이 행 잠금을 쥔 채 커밋을 미루는 동안 다른 트랜잭션이 그 행을 기다리게 해서 재현한다.
 */
class ChatMessageConcurrencyTest extends ChatIntegrationTestSupport {

    private static final String FAILED_CONTENT = "답변을 만들지 못했어요";
    private static final OffsetDateTime FUTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

    @Autowired
    ChatMessageRepository messages;

    private long pendingAnswer() {
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        return insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);
    }

    private int complete(long id) {
        return messages.complete(id, memberId, "ANSWER", "답변", null, "AVG", null);
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
        long id = pendingAnswer();
        delete();

        assertThat(complete(id)).isZero();
        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("오래된 PENDING 정리 뒤에 온 답변 완료는 0행이고 FAILED를 덮지 않는다")
    void completionAfterCleanupIsIgnored() {
        long id = pendingAnswer();
        assertThat(cleanUp()).isEqualTo(1);

        assertThat(complete(id)).isZero();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(contentOf(id)).isEqualTo(FAILED_CONTENT);
    }

    @Test
    @DisplayName("답변 완료 뒤의 정리와 실패 처리는 0행이고 COMPLETED를 바꾸지 않는다")
    void cleanupAndFailAfterCompletionAreIgnored() {
        long id = pendingAnswer();
        assertThat(complete(id)).isEqualTo(1);

        assertThat(cleanUp()).isZero();
        assertThat(messages.fail(id, memberId, FAILED_CONTENT)).isZero();
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(contentOf(id)).isEqualTo("답변");
    }

    // ---------- 동시(행 잠금 대기) ----------

    @Test
    @DisplayName("답변 완료가 잠금을 쥔 동안 기다리던 로그아웃 삭제는 완료 커밋 뒤 그 행을 지운다")
    void deletionWaitingOnCompletionStillDeletes() throws Exception {
        long id = pendingAnswer();

        Outcome outcome = whileFirstHoldsLock(() -> complete(id), this::delete);

        assertThat(outcome.first()).isEqualTo(1);
        assertThat(outcome.second()).isEqualTo(2);
        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("로그아웃 삭제가 잠금을 쥔 동안 기다리던 답변 완료는 0행이고 행을 되살리지 않는다")
    void completionWaitingOnDeletionIsIgnored() throws Exception {
        long id = pendingAnswer();

        Outcome outcome = whileFirstHoldsLock(this::delete, () -> complete(id));

        assertThat(outcome.second()).isZero();
        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("정리가 잠금을 쥔 동안 기다리던 답변 완료는 0행이고 FAILED로 남는다")
    void completionWaitingOnCleanupIsIgnored() throws Exception {
        long id = pendingAnswer();

        Outcome outcome = whileFirstHoldsLock(this::cleanUp, () -> complete(id));

        assertThat(outcome.first()).isEqualTo(1);
        assertThat(outcome.second()).isZero();
        assertThat(statusOf(id)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("답변 완료가 잠금을 쥔 동안 기다리던 정리는 0행이고 COMPLETED로 남는다")
    void cleanupWaitingOnCompletionIsIgnored() throws Exception {
        long id = pendingAnswer();

        Outcome outcome = whileFirstHoldsLock(() -> complete(id), this::cleanUp);

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
}
