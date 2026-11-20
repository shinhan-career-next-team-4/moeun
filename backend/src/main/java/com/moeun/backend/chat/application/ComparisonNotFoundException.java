package com.moeun.backend.chat.application;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 없는 id·남의 메시지·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지. 어느 경우인지 구분되지 않는 404 NOT_FOUND(id 추측 방지). */
public class ComparisonNotFoundException extends ChatRequestException {

    public ComparisonNotFoundException() {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", List.of());
    }
}
