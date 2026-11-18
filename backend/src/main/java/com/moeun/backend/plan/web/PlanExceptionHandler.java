package com.moeun.backend.plan.web;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moeun.backend.plan.application.PlanConflictException;
import com.moeun.backend.plan.application.PlanValidationException;

@Order(Ordered.HIGHEST_PRECEDENCE)   // 공통 처리기(config)의 포괄 처리보다 먼저 본다
@RestControllerAdvice(basePackageClasses = PlanController.class)
class PlanExceptionHandler {

    @ExceptionHandler(PlanValidationException.class)
    ProblemDetail validation(PlanValidationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "입력값을 확인해 주세요.");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", exception.errors());
        return problem;
    }

    @ExceptionHandler(PlanConflictException.class)
    ProblemDetail conflict(PlanConflictException exception) {
        String detail = switch (exception.code()) {
            case PlanConflictException.NO_PLAN -> "저장된 계획이 없어요.";
            case PlanConflictException.NOTHING_TO_UNDO -> "되돌릴 직전 계획이 없어요.";
            default -> "그사이 계획이 바뀌었어요.";
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setProperty("code", exception.code());
        problem.setProperty("errors", List.of());
        problem.setProperty("plan", exception.current().orElse(null));
        return problem;
    }
}
