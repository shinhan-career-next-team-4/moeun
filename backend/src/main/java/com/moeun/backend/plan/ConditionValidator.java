package com.moeun.backend.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.application.PlanRules;

/**
 * 대안·비교·대화에서 바꾼 조건 검증(CP-02, CP-07). 입력 화면과 같은 범위를 쓴다.
 * 계산 담당과 AI 담당도 조건을 바꾸기 전에 이 클래스를 주입해 호출한다.
 */
@Component
public class ConditionValidator {

    private static final String CHANGES = "changes";

    private final PlanRules rules;

    public ConditionValidator(PlanRules rules) {
        this.rules = rules;
    }

    public List<FieldError> validate(ChangePath path, ConditionOverride changes) {
        Map<InputField, BigDecimal> changed = changedFields(changes);
        if (changed.isEmpty()) {
            return List.of(FieldError.required(CHANGES));
        }
        PlanRules.PathRule pathRule = rules.changePaths().get(path);
        List<FieldError> errors = new ArrayList<>();
        if (pathRule.maxFields() > 0 && changed.size() > pathRule.maxFields()) {
            errors.add(FieldError.of(CHANGES, "TOO_MANY_CHANGES"));
            return errors;
        }
        changed.forEach((field, value) -> {
            if (!pathRule.allows(field)) {
                errors.add(FieldError.of(field.apiName(), "NOT_CHANGEABLE"));
                return;
            }
            rules.check(field, value).ifPresent(errors::add);
        });
        return errors;
    }

    private static Map<InputField, BigDecimal> changedFields(ConditionOverride changes) {
        Map<InputField, BigDecimal> changed = new LinkedHashMap<>();
        if (changes == null) {
            return changed;
        }
        putIfPresent(changed, InputField.MONTHLY_SAVING_AMOUNT, changes.monthlySaving());
        putIfPresent(changed, InputField.TARGET_PERIOD_MONTHS, changes.targetPeriodMonths());
        putIfPresent(changed, InputField.SAVINGS_RATIO_PCT, changes.savingsRatioPct());
        putIfPresent(changed, InputField.SAVINGS_RATE_INPUT_PCT, changes.savingsRatePct());
        return changed;
    }

    private static void putIfPresent(Map<InputField, BigDecimal> map, InputField field, Number value) {
        if (value != null) {
            map.put(field, value instanceof BigDecimal d ? d : new BigDecimal(value.toString()));
        }
    }
}
