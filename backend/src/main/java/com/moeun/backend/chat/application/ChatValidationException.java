package com.moeun.backend.chat.application;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.moeun.backend.plan.FieldError;

/** 질문 text 검증 실패. 422 VALIDATION_FAILED. */
public class ChatValidationException extends ChatRequestException {

    public ChatValidationException(FieldError error) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", List.of(error));
    }
}
