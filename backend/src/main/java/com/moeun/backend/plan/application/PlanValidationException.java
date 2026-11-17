package com.moeun.backend.plan.application;

import java.util.List;

import com.moeun.backend.plan.FieldError;

/** 입력 검증 실패. 컨트롤러가 400 + errors로 바꾼다. */
public class PlanValidationException extends RuntimeException {

    private final transient List<FieldError> errors;

    public PlanValidationException(List<FieldError> errors) {
        super("plan validation failed: " + errors);
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
