package com.moeun.backend.chat.application;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 질문 답변이 아직 PENDING인데 또 질문을 보냈다. 409 ANSWER_PENDING. */
public class AnswerPendingException extends ChatRequestException {

    public AnswerPendingException() {
        super(HttpStatus.CONFLICT, "ANSWER_PENDING", List.of());
    }
}
