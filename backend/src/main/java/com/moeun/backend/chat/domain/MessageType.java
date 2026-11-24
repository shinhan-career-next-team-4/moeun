package com.moeun.backend.chat.domain;

import java.util.EnumSet;
import java.util.Set;

/** 메시지 종류(schema.sql ck_cm_type). */
public enum MessageType {
    QUESTION,
    ANSWER,
    EXPLANATION,
    RECALC_RESULT,
    CLARIFY,
    OUT_OF_SCOPE,
    FAILURE;

    private static final Set<MessageType> ANSWER_TYPES = EnumSet.of(ANSWER, EXPLANATION, RECALC_RESULT, CLARIFY,
            OUT_OF_SCOPE);

    /** ai-server가 돌려줄 수 있는 답의 종류. 질문(QUESTION)과 실패(FAILURE)는 Spring이 정한다. */
    public static Set<MessageType> answerTypes() {
        return EnumSet.copyOf(ANSWER_TYPES);
    }

    /** ai-server 응답의 messageType 문자열이 답의 종류인지. 모르는 값·null이면 false다. */
    public static boolean isAnswerName(String name) {
        return ANSWER_TYPES.stream().anyMatch(type -> type.name().equals(name));
    }

    public boolean isAnswer() {
        return ANSWER_TYPES.contains(this);
    }
}
