package com.moeun.backend.plan.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moeun.backend.plan.application.PlanConflictException;
import com.moeun.backend.plan.application.PlanValidationException;

@RestControllerAdvice(basePackageClasses = PlanController.class)
class PlanExceptionHandler {

    @ExceptionHandler(PlanValidationException.class)
    ProblemDetail validation(PlanValidationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요.");
        problem.setProperty("errors", exception.errors());
        return problem;
    }

    @ExceptionHandler(PlanConflictException.class)
    ProblemDetail conflict(PlanConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "그사이 계획이 바뀌었어요.");
        problem.setProperty("plan", exception.current().orElse(null));
        return problem;
    }
}
