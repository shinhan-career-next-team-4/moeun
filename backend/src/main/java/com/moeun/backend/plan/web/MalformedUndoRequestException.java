package com.moeun.backend.plan.web;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.moeun.backend.plan.PlanProblemException;

/** 되돌리기 요청 본문의 expectedRevision이 없거나 정수가 아니다(검사 1). 400 MALFORMED_REQUEST. */
class MalformedUndoRequestException extends PlanProblemException {

    MalformedUndoRequestException() {
        super(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "요청 본문을 읽을 수 없어요.", List.of());
    }
}
