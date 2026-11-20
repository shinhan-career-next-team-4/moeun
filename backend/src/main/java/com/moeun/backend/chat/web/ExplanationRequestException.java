package com.moeun.backend.chat.web;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.moeun.backend.chat.application.ChatRequestException;

/** F-3 요청 형식 오류. 400 MALFORMED_REQUEST·405 METHOD_NOT_ALLOWED·415 UNSUPPORTED_MEDIA_TYPE, 모두 errors=[]. */
class ExplanationRequestException extends ChatRequestException {

    private ExplanationRequestException(HttpStatus status, String code) {
        super(status, code, List.of());
    }

    static ExplanationRequestException malformed() {
        return new ExplanationRequestException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    static ExplanationRequestException methodNotAllowed() {
        return new ExplanationRequestException(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    static ExplanationRequestException unsupportedMediaType() {
        return new ExplanationRequestException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
    }
}
