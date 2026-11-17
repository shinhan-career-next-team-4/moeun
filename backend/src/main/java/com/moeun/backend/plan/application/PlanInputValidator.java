package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.InputField;
import com.moeun.backend.plan.RatioChoice;

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
        checkOptional(InputField.TARGET_PERIOD_MONTHS, in.targetPeriodMonths(), errors);
        checkOptional(InputField.MONTHLY_SAVING, in.monthlySaving(), errors);
        checkOptional(InputField.SAVINGS_RATE_PCT, in.savingsRatePct(), errors);
        if (in.targetPeriodMonths() == null && in.monthlySaving() == null) {
            errors.add(new FieldError(InputField.TARGET_PERIOD_MONTHS.apiName(), "ONE_OF_PERIOD_OR_SAVING"));
        }
        if (in.existingOn()) {
            checkExisting(in, errors);
        }
        return errors;
    }

    /** 저장할 비중. '지금 비중 유지'를 고른 경우만 값이 있고, 나머지는 null(기준 비중 사용, CF-08). */
    public Integer resolveSavingsRatioPct(PlanInput in) {
        long sum = in.existingSum();
        if (in.ratioChoice() != RatioChoice.KEEP_CURRENT || sum == 0) {
            return null;
        }
        long savings = in.existingSavingsMonthly() == null ? 0L : in.existingSavingsMonthly();
        return (int)Math.round(savings * 100.0 / sum);
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

    private void checkExisting(PlanInput in, List<FieldError> errors) {
        checkOptional(InputField.EXISTING_SAVINGS_MONTHLY, in.existingSavingsMonthly(), errors);
        checkOptional(InputField.EXISTING_ETF_MONTHLY, in.existingEtfMonthly(), errors);
        long sum = in.existingSum();
        if (sum == 0) {
            return;
        }
        if (in.monthlySaving() != null && sum > in.monthlySaving()) {
            errors.add(new FieldError(InputField.EXISTING_SAVINGS_MONTHLY.apiName(), "EXISTING_EXCEEDS_SAVING"));
        }
        if (in.ratioChoice() == null) {
            errors.add(new FieldError("ratioChoice", "RATIO_CHOICE_REQUIRED"));
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
