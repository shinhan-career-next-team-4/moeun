package com.moeun.backend.chat.application;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 없는 기록·남의 기록·PLAN이 아닌 기록. 어느 경우인지 구분되지 않는 404 NOT_FOUND(id 추측 방지). */
public class ExplanationRunNotFoundException extends ChatRequestException {

    public ExplanationRunNotFoundException() {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", List.of());
    }
}
