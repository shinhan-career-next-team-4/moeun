package com.moeun.backend.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 메시지 상태 전이 규칙(#312). 스프링 없이 돈다. 저장은 PENDING일 때만 되는 조건부 UPDATE라 동시성은 저장소가 지킨다. */
class ChatMessageTest {

    static final long ID = 10L;
    static final long MEMBER_ID = 7L;

    static ChatMessage pendingAnswer() {
        return new ChatMessage(ID, MEMBER_ID, MessageType.ANSWER, MessageStatus.PENDING, null, null);
    }

    static ChatMessage pendingExplanation() {
        return new ChatMessage(ID, MEMBER_ID, MessageType.EXPLANATION, MessageStatus.PENDING, 55L, "GOOD");
    }

    @Test
    void completeMovesPendingToCompletedWithAnswer() {
        MessageTransition done = pendingAnswer().complete(MessageType.RECALC_RESULT, "다시 계산했어요", 77L, "AVG",
                "{\"monthlySavingAmount\":500000}");

        assertThat(done.id()).isEqualTo(ID);
        assertThat(done.memberId()).isEqualTo(MEMBER_ID);
        assertThat(done.from()).isEqualTo(MessageStatus.PENDING);
        assertThat(done.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(done.type()).isEqualTo(MessageType.RECALC_RESULT);
        assertThat(done.content()).isEqualTo("다시 계산했어요");
        assertThat(done.calcRunId()).isEqualTo(77L);
        assertThat(done.baselineScenario()).isEqualTo("AVG");
        assertThat(done.conditionChangesJson()).isEqualTo("{\"monthlySavingAmount\":500000}");
    }

    @Test
    void completeKeepsStoredCalcRunAndBaselineWhenAnswerLeavesThemEmpty() {
        MessageTransition done = pendingExplanation().complete(MessageType.EXPLANATION, "설명", null, null, null);

        assertThat(done.calcRunId()).isEqualTo(55L);
        assertThat(done.baselineScenario()).isEqualTo("GOOD");
    }

    @Test
    void outOfScopeAnswerClearsCalcRun() {
        MessageTransition done = pendingExplanation().complete(MessageType.OUT_OF_SCOPE, "도와드릴 수 없어요", 77L, null,
                null);

        assertThat(done.calcRunId()).as("ck_cm_out_of_scope: 범위 밖 답은 계산 기록을 가리키지 않는다").isNull();
        assertThat(done.baselineScenario()).isEqualTo("GOOD");
    }

    @Test
    void completeRejectsNonAnswerTypes() {
        assertThatThrownBy(() -> pendingAnswer().complete(MessageType.QUESTION, "질문", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pendingAnswer().complete(MessageType.FAILURE, "실패", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failMovesPendingToFailedAndKeepsCalcRun() {
        MessageTransition failed = pendingExplanation().fail("답변을 만들지 못했어요");

        assertThat(failed.from()).isEqualTo(MessageStatus.PENDING);
        assertThat(failed.status()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.type()).isEqualTo(MessageType.FAILURE);
        assertThat(failed.content()).isEqualTo("답변을 만들지 못했어요");
        assertThat(failed.calcRunId()).as("실패한 설명도 계산 기록을 가져 '다시 요청' 원본이 된다").isEqualTo(55L);
        assertThat(failed.baselineScenario()).isEqualTo("GOOD");
        assertThat(failed.conditionChangesJson()).isNull();
    }

    @Test
    void finishedMessagesCannotBeCompletedOrFailedAgain() {
        for (MessageStatus finished : new MessageStatus[] {MessageStatus.COMPLETED, MessageStatus.FAILED}) {
            ChatMessage message = new ChatMessage(ID, MEMBER_ID, MessageType.ANSWER, finished, null, null);

            assertThat(message.isPending()).isFalse();
            assertThatThrownBy(() -> message.complete(MessageType.ANSWER, "답", null, null, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> message.fail("실패")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void answerTypesAreWhatAiServerMayReturn() {
        assertThat(MessageType.answerTypes()).containsExactlyInAnyOrder(MessageType.ANSWER, MessageType.EXPLANATION,
                MessageType.RECALC_RESULT, MessageType.CLARIFY, MessageType.OUT_OF_SCOPE);
        assertThat(MessageType.isAnswerName("ANSWER")).isTrue();
        assertThat(MessageType.isAnswerName("QUESTION")).isFalse();
        assertThat(MessageType.isAnswerName("NOPE")).isFalse();
        assertThat(MessageType.isAnswerName(null)).isFalse();
    }
}
