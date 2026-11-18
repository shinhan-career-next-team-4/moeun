package com.moeun.backend.calc.web;

import java.util.List;

import com.moeun.backend.plan.FieldError;

/** null은 허용하지만 키는 꼭 있어야 하는 항목이 빠졌다(422 VALIDATION_FAILED + REQUIRED, 계약 C12a-5-1). */
class MissingPlanKeysException extends RuntimeException {

    private final transient List<FieldError> errors;

    MissingPlanKeysException(List<FieldError> errors) {
        super("required plan keys are missing");
        this.errors = List.copyOf(errors);
    }

    List<FieldError> errors() {
        return errors;
    }
}
