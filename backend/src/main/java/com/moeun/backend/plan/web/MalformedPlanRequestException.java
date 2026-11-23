package com.moeun.backend.plan.web;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.moeun.backend.plan.PlanProblemException;

/** 되돌리기·적용 요청 본문을 읽을 수 없다(검사 1, C12a-8·C12a-9-1). 400 MALFORMED_REQUEST. */
class MalformedPlanRequestException extends PlanProblemException {

    MalformedPlanRequestException() {
        super(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "요청 본문을 읽을 수 없어요.", List.of());
    }
}
