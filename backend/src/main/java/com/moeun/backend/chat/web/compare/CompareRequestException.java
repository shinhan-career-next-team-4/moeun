package com.moeun.backend.chat.web.compare;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.moeun.backend.chat.application.ChatRequestException;
import com.moeun.backend.plan.FieldError;

/**
 * 조건 변경 비교 요청 오류. 본문은 ProblemDetail + code + errors[]이고 채팅 처리기가 그대로 내보낸다.
 * 400 MALFORMED_REQUEST, 422 VALIDATION_FAILED, 409 NO_PLAN·ALREADY_EXCEEDED.
 */
class CompareRequestException extends ChatRequestException {

    private CompareRequestException(HttpStatus status, String code, List<FieldError> errors, String detail) {
        super(status, code, errors);
        if (detail != null) {
            getBody().setDetail(detail);
        }
    }

    static CompareRequestException malformed() {
        return new CompareRequestException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", List.of(), null);
    }

    static CompareRequestException validationFailed(List<FieldError> errors, String detail) {
        return new CompareRequestException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", errors, detail);
    }

    static CompareRequestException conflict(String code, String detail) {
        return new CompareRequestException(HttpStatus.CONFLICT, code, List.of(), detail);
    }
}
