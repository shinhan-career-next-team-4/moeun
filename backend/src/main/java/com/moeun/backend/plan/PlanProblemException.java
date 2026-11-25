package com.moeun.backend.plan;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * plan 모듈이 그대로 오류 본문이 되는 예외의 공통 부모(계약 C12a-10).
 * {@code ErrorResponseException}을 상속해 {@code code}·{@code errors}를 직접 담는다.
 * 그래서 config 전역 advice를 고치지 않는다. 상태와 코드는 계약이 정한 값이다.
 */
public abstract class PlanProblemException extends ErrorResponseException {

    private final String code;

    protected PlanProblemException(HttpStatus status, String code, String detail, List<FieldError> errors) {
        super(status, body(status, code, detail, errors), null);
        this.code = code;
    }

    private static ProblemDetail body(HttpStatus status, String code, String detail, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("errors", errors);
        return problem;
    }

    public String code() {
        return code;
    }
}
