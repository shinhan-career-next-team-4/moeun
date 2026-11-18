package com.moeun.backend.calc.web;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 입력 저장 REST의 형식·키 오류를 계약 오류 본문(ProblemDetail + code + errors[])으로 바꾼다(C12a-10).
 * <p>이 컨트롤러에만 적용한다. {@code /internal/calc}의 {@code minDisplay}·{@code maxDisplay}와 {@code CALC_FAILED}는
 * 그 컨트롤러의 처리기가 맡아 이 API 오류에 섞이지 않는다. 검증(422)·revision(409) 오류는 plan 모듈 처리기가,
 * 그 밖의 오류는 공통 처리기(500 INTERNAL_ERROR)가 맡는다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PlanResultController.class)
class PlanResultExceptionHandler {

    @ExceptionHandler(MalformedPlanRequestException.class)
    ProblemDetail malformed(MalformedPlanRequestException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요.");
        problem.setProperty("code", "MALFORMED_REQUEST");
        problem.setProperty("errors", List.of());
        return problem;
    }

    @ExceptionHandler(MissingPlanKeysException.class)
    ProblemDetail missingKeys(MissingPlanKeysException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "입력값을 확인해 주세요.");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", exception.errors());
        return problem;
    }
}
