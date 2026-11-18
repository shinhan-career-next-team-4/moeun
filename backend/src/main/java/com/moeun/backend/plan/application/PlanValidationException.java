package com.moeun.backend.plan.application;

import java.util.List;

import com.moeun.backend.plan.FieldError;

/** 입력 검증 실패. 컨트롤러가 422 + code + errors로 바꾼다. */
public class PlanValidationException extends RuntimeException {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String FIELD_NOT_CHANGEABLE = "FIELD_NOT_CHANGEABLE";

    private static final String NOT_CHANGEABLE_REASON = "NOT_CHANGEABLE";

    private final transient List<FieldError> errors;
    private final String code;

    public PlanValidationException(List<FieldError> errors) {
        super("plan validation failed: " + errors);
        this.errors = List.copyOf(errors);
        this.code = codeOf(this.errors);
    }

    /** 허용 밖 키가 하나라도 있으면 FIELD_NOT_CHANGEABLE이다(D-040). */
    private static String codeOf(List<FieldError> errors) {
        return errors.stream().anyMatch(error -> NOT_CHANGEABLE_REASON.equals(error.reason()))
                ? FIELD_NOT_CHANGEABLE : VALIDATION_FAILED;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
