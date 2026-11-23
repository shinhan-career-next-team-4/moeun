package com.moeun.backend.plan.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.InputField;
import com.moeun.backend.plan.PlanInput;
import com.moeun.backend.plan.application.PlanRules.FieldRule;

import tools.jackson.databind.JsonNode;

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
        Map<String, JsonNode> fields = in.fields();
        checkRequired(InputField.GOAL_AMOUNT, fields, errors);
        checkRequired(InputField.HELD_ASSETS, fields, errors);
        checkRequired(InputField.TARGET_PERIOD_MONTHS, fields, errors);
        checkRequired(InputField.MONTHLY_SAVING_AMOUNT, fields, errors);
        checkOptional(InputField.SAVINGS_RATE_INPUT_PCT, fields, errors);
        // 비중은 키가 있어야 하지만(컨트롤러가 REQUIRED를 본다) 값은 null일 수 있다(C12a-5-1, D-086)
        checkOptional(InputField.SAVINGS_RATIO_PCT, fields, errors);
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

    private void checkRequired(InputField field, Map<String, JsonNode> fields, List<FieldError> errors) {
        JsonNode node = fields.get(field.apiName());
        if (node == null || node.isNull()) {
            errors.add(FieldError.required(field.apiName()));
            return;
        }
        checkNode(field, node, errors);
    }

    private void checkOptional(InputField field, Map<String, JsonNode> fields, List<FieldError> errors) {
        JsonNode node = fields.get(field.apiName());
        if (node == null || node.isNull()) {
            return;
        }
        checkNode(field, node, errors);
    }

    private void checkNode(InputField field, JsonNode node, List<FieldError> errors) {
        if (!node.isNumber()) {
            errors.add(FieldError.notInteger(field.apiName()));
            return;
        }
        rules.check(field, node.decimalValue()).ifPresent(errors::add);
    }
}
