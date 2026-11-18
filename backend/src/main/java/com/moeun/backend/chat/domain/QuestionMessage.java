package com.moeun.backend.chat.domain;

/** 사용자가 보낸 질문 행. */
public record QuestionMessage(long id, String content) {
}
