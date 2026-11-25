package com.moeun.backend.plan;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 입력값·조건 변경값 검증 실패(적용 검사 6·7). 422 VALIDATION_FAILED, 허용 밖 키가 있으면 FIELD_NOT_CHANGEABLE.
 * errors[]에는 field·reason과 OUT_OF_RANGE의 min·max·unit, TOO_MANY_DECIMALS의 scale만 싣는다(계약 C12a-5-4).
 */
public class ConditionViolationException extends PlanProblemException {

    private static final String NOT_CHANGEABLE_REASON = "NOT_CHANGEABLE";

    private final transient List<FieldError> errors;

    public ConditionViolationException(List<FieldError> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, codeOf(errors), "입력값을 확인해 주세요.", List.copyOf(errors));
        this.errors = List.copyOf(errors);
    }

    /** 허용 밖 키가 하나라도 있으면 FIELD_NOT_CHANGEABLE이다(D-040). */
    private static String codeOf(List<FieldError> errors) {
        return errors.stream().anyMatch(error -> NOT_CHANGEABLE_REASON.equals(error.reason()))
                ? "FIELD_NOT_CHANGEABLE" : "VALIDATION_FAILED";
    }

    public List<FieldError> errors() {
        return errors;
    }
}
