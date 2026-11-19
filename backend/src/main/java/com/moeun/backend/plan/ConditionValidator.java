package com.moeun.backend.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.application.PlanRules;

import tools.jackson.databind.JsonNode;

/**
 * 대안·비교·대화에서 바꾼 조건 검증(CP-02, CP-07). 입력 화면과 같은 범위를 쓴다.
 * 계산 담당과 AI 담당도 조건을 바꾸기 전에 이 클래스를 주입해 호출한다.
 */
@Component
public class ConditionValidator {

    /** 계약 C12a의 요청 키. 바꿀 값이 하나도 없을 때 errors의 field로 쓴다. */
    private static final String CHANGES = "conditionChanges";

    private final PlanRules rules;

    public ConditionValidator(PlanRules rules) {
        this.rules = rules;
    }

    public List<FieldError> validate(ChangePath path, ConditionOverride changes) {
        return validateChanged(path, changedFields(changes));
    }

    /**
     * 적용 경로가 쓰는 검증(계약 C12b-7-1 검사 6·7). 키는 API와 같은 camelCase다.
     * 허용 밖 키나 숫자로 읽을 수 없는 값이 하나라도 있으면 일부만 적용하지 않고 전체를 거절한다.
     */
    public List<FieldError> validateChanges(ChangePath path, Map<String, JsonNode> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of(FieldError.required(CHANGES));
        }
        Map<InputField, BigDecimal> changed = new LinkedHashMap<>();
        List<FieldError> keyErrors = new ArrayList<>();
        changes.forEach((key, value) -> {
            if (value == null || value.isNull()) {
                return;
            }
            InputField field = fieldOf(key);
            if (field == null) {
                keyErrors.add(FieldError.notChangeable(key));
            } else if (!isFiniteNumber(value)) {
                keyErrors.add(FieldError.notInteger(key));
            } else {
                changed.put(field, value.decimalValue());
            }
        });
        if (!keyErrors.isEmpty()) {
            return keyErrors;
        }
        return validateChanged(path, changed);
    }

    private static InputField fieldOf(String apiName) {
        for (InputField field : InputField.values()) {
            if (field.apiName().equals(apiName)) {
                return field;
            }
        }
        return null;
    }

    private static boolean isFiniteNumber(JsonNode node) {
        return node.isNumber() && (!node.isFloatingPointNumber() || Double.isFinite(node.doubleValue()));
    }

    private List<FieldError> validateChanged(ChangePath path, Map<InputField, BigDecimal> changed) {
        if (changed.isEmpty()) {
            return List.of(FieldError.required(CHANGES));
        }
        PlanRules.PathRule pathRule = rules.changePaths().get(path);
        List<FieldError> errors = new ArrayList<>();
        if (pathRule.maxFields() > 0 && changed.size() > pathRule.maxFields()) {
            errors.add(FieldError.tooManyChanges(CHANGES));
            return errors;
        }
        changed.forEach((field, value) -> {
            if (!pathRule.allows(field)) {
                errors.add(FieldError.notChangeable(field.apiName()));
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
        putIfPresent(changed, InputField.MONTHLY_SAVING_AMOUNT, changes.monthlySavingAmount());
        putIfPresent(changed, InputField.TARGET_PERIOD_MONTHS, changes.targetPeriodMonths());
        putIfPresent(changed, InputField.SAVINGS_RATIO_PCT, changes.savingsRatioPct());
        putIfPresent(changed, InputField.SAVINGS_RATE_INPUT_PCT, changes.savingsRateInputPct());
        return changed;
    }

    private static void putIfPresent(Map<InputField, BigDecimal> map, InputField field, Number value) {
        if (value != null) {
            map.put(field, value instanceof BigDecimal d ? d : new BigDecimal(value.toString()));
        }
    }
}
