package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.InputField;
import com.moeun.backend.plan.application.PlanRules.FieldRule;

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
            errors.add(FieldError.required(InputField.GOAL_TYPE.apiName()));
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

    /** 길이는 앞뒤 공백을 뗀 뒤 코드포인트로 센다. 비었거나 100자를 넘으면 OUT_OF_RANGE(D-074). */
    private void checkCustomName(String name, List<FieldError> errors) {
        if (name == null) {
            errors.add(FieldError.required(InputField.GOAL_CUSTOM_NAME.apiName()));
            return;
        }
        String trimmed = name.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        FieldRule rule = rules.fields().get(InputField.GOAL_CUSTOM_NAME);
        if (length < rule.min().intValue() || length > rule.max().intValue()) {
            errors.add(FieldError.outOfRange(InputField.GOAL_CUSTOM_NAME, rule));
        }
    }

    private void checkRequired(InputField field, Number value, List<FieldError> errors) {
        if (value == null) {
            errors.add(FieldError.required(field.apiName()));
            return;
        }
        checkOptional(field, value, errors);
    }

    private void checkOptional(InputField field, Number value, List<FieldError> errors) {
        if (value == null) {
            return;
        }
        BigDecimal decimal = value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
        rules.check(field, decimal).ifPresent(errors::add);
    }
}
