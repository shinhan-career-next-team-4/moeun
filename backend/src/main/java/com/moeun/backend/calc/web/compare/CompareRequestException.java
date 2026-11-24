package com.moeun.backend.calc.web.compare;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import com.moeun.backend.plan.FieldError;

/**
 * 조건 변경 비교 요청 오류. 본문은 ProblemDetail + code + errors[]이고 {@link CompareExceptionHandler}가 그대로 내보낸다.
 * 400 MALFORMED_REQUEST, 422 VALIDATION_FAILED, calc가 거절하면 그 상태(409 NO_PLAN·ALREADY_EXCEEDED).
 */
class CompareRequestException extends ErrorResponseException {

    private CompareRequestException(HttpStatus status, String code, List<FieldError> errors, String detail) {
        super(status, problem(status, code, errors, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, List<FieldError> errors, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        problem.setProperty("errors", errors);
        if (detail != null) {
            problem.setDetail(detail);
        }
        return problem;
    }

    static CompareRequestException malformed() {
        return new CompareRequestException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", List.of(), null);
    }

    static CompareRequestException validationFailed(List<FieldError> errors, String detail) {
        return new CompareRequestException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", errors, detail);
    }

    /** calc가 거절한 비교. 상태·코드는 calc가 정한 대로 내보낸다(지금은 409 NO_PLAN·ALREADY_EXCEEDED). */
    static CompareRequestException rejected(int status, String code, String detail) {
        return new CompareRequestException(HttpStatus.valueOf(status), code, List.of(), detail);
    }
}
