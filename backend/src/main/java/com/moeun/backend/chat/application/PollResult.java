package com.moeun.backend.chat.application;

import java.util.List;

/** F-2 응답. hasPending은 목록 밖을 포함한 PENDING 유무, answerPending은 질문 답변 대기 여부다. */
public record PollResult(List<PolledMessage> messages, boolean hasPending, boolean answerPending) {
}
