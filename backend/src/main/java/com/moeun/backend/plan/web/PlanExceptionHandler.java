package com.moeun.backend.plan.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moeun.backend.plan.PlanProblemException;

/**
 * plan 예외가 들고 있는 오류 본문을 그대로 내보낸다. 예외가 code·errors를 직접 담으므로 여기서 짓지 않는다.
 * 전역 처리기(config)는 ErrorResponseException의 code를 MALFORMED_REQUEST로 덮어쓰기 때문에 먼저 본다.
 * 입력 저장(PUT /api/plan, calc.web)도 plan 저장 서비스를 부르므로 컨트롤러를 가리지 않는다(#30).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class PlanExceptionHandler {

    @ExceptionHandler(PlanProblemException.class)
    ResponseEntity<ProblemDetail> problem(PlanProblemException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(exception.getBody());
    }
}
