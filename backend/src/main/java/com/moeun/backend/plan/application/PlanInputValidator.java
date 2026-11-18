package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.InputField;

/** 입력 화면 전체 검증(FR-INP-09, CP-02). 항목별 범위는 PlanRules, 항목 간 규칙은 이 클래스가 맡는다. */
@Component
public class PlanInputValidator {

    private final PlanRules rules;

    public PlanInputValidator(PlanRules rules) {
        this.rules = rules;
    }

    public List<FieldError> validate(PlanInput in) {
        List<FieldError> errors = new ArrayList<>();
        if (in.goalType() == null) {
            errors.add(new FieldError(InputField.GOAL_TYPE.apiName(), "REQUIRED"));
        } else if (in.goalType() == GoalType.CUSTOM) {
            checkCustomName(in.goalCustomName(), errors);
        }
        checkRequired(InputField.GOAL_AMOUNT, in.goalAmount(), errors);
        checkRequired(InputField.HELD_ASSETS, in.heldAssets(), errors);
        checkRequired(InputField.TARGET_PERIOD_MONTHS, in.targetPeriodMonths(), errors);
        checkRequired(InputField.MONTHLY_SAVING_AMOUNT, in.monthlySavingAmount(), errors);
        checkOptional(InputField.SAVINGS_RATE_INPUT_PCT, in.savingsRateInputPct(), errors);
        return errors;
    }

    private void checkCustomName(String name, List<FieldError> errors) {
        String field = InputField.GOAL_CUSTOM_NAME.apiName();
        if (name == null) {
            errors.add(new FieldError(field, "REQUIRED"));
            return;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            errors.add(new FieldError(field, "BLANK"));
        } else if (trimmed.codePointCount(0, trimmed.length())
                > rules.fields().get(InputField.GOAL_CUSTOM_NAME).max().intValue()) {
            errors.add(new FieldError(field, "TOO_LONG"));
        }
    }

    private void checkRequired(InputField field, Number value, List<FieldError> errors) {
        if (value == null) {
            errors.add(new FieldError(field.apiName(), "REQUIRED"));
            return;
        }
        checkOptional(field, value, errors);
    }

    private void checkOptional(InputField field, Number value, List<FieldError> errors) {
        if (value == null) {
            return;
        }
        BigDecimal decimal = value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
        rules.check(field, decimal).ifPresent(code -> errors.add(new FieldError(field.apiName(), code)));
    }
}
