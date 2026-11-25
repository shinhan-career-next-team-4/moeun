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
    private final EtfProductCatalog etfProducts;

    public ConditionValidator(PlanRules rules, EtfProductCatalog etfProducts) {
        this.rules = rules;
        this.etfProducts = etfProducts;
    }

    public List<FieldError> validate(ChangePath path, ConditionOverride changes) {
        return validateChanged(path, changedFields(changes));
    }

    /**
     * 적용 경로가 쓰는 검증(계약 C12b-7-1 검사 6·7). 키는 API와 같은 camelCase다.
     * 허용 밖 키나 숫자로 읽을 수 없는 값이 하나라도 있으면 일부만 적용하지 않고 전체를 거절한다.
     * 종목 코드({@code etfProductCode})와 목표 이름({@code goalCustomName})은 문자열이다. 종목은 적재된 종목인지로(#154),
     * 이름은 입력 화면과 같은 글자 수로(#345) 검증한다. 이름은 계산 조건이 아니라 바꾼 개수에 세지 않는다.
     */
    public List<FieldError> validateChanges(ChangePath path, Map<String, JsonNode> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of(FieldError.required(CHANGES));
        }
        Map<InputField, BigDecimal> changed = new LinkedHashMap<>();
        List<FieldError> keyErrors = new ArrayList<>();
        JsonNode etfProduct = null;
        JsonNode goalCustomName = null;
        for (Map.Entry<String, JsonNode> entry : changes.entrySet()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            InputField field = fieldOf(key);
            if (field == null) {
                keyErrors.add(FieldError.notChangeable(key));
            } else if (field == InputField.ETF_PRODUCT_CODE) {
                etfProduct = value;
            } else if (field == InputField.GOAL_CUSTOM_NAME) {
                goalCustomName = value;
            } else if (!isFiniteNumber(value)) {
                keyErrors.add(FieldError.notInteger(key));
            } else {
                changed.put(field, value.decimalValue());
            }
        }
        if (!keyErrors.isEmpty()) {
            return keyErrors;
        }
        if (goalCustomName == null) {
            return validateCalcConditions(path, changed, etfProduct);
        }
        List<FieldError> errors = new ArrayList<>(validateGoalCustomName(path,
                goalCustomName.isString() ? goalCustomName.asString() : ""));
        if (!changed.isEmpty() || etfProduct != null) {
            errors.addAll(validateCalcConditions(path, changed, etfProduct));
        }
        return errors;
    }

    /**
     * 목표 이름(#345). 경로 허용 → 입력 화면과 같은 글자 수(앞뒤 공백을 뗀 1~100자, D-074) 순서로 본다.
     * 문자열이 아닌 값은 부르는 쪽이 빈 이름으로 넘긴다(이름으로 쓸 수 없는 값이라 OUT_OF_RANGE).
     */
    public List<FieldError> validateGoalCustomName(ChangePath path, String name) {
        if (!rules.changePaths().get(path).allows(InputField.GOAL_CUSTOM_NAME)) {
            return List.of(FieldError.notChangeable(InputField.GOAL_CUSTOM_NAME.apiName()));
        }
        return rules.checkText(InputField.GOAL_CUSTOM_NAME, name).stream().toList();
    }

    private List<FieldError> validateCalcConditions(ChangePath path, Map<InputField, BigDecimal> changed,
            JsonNode etfProduct) {
        if (etfProduct == null) {
            return validateChanged(path, changed);
        }
        return validateWithEtfProduct(path, changed, etfProduct);
    }

    /**
     * 종목을 함께 바꾼 경우. 숫자 항목과 같이 바꾼 개수를 먼저 보고, 그다음 경로 허용 → 적재된 종목 순서로 본다.
     * 종목 오류를 앞에 두고 숫자 항목 오류를 뒤에 붙인다.
     */
    private List<FieldError> validateWithEtfProduct(ChangePath path, Map<InputField, BigDecimal> changed,
            JsonNode etfProduct) {
        PlanRules.PathRule pathRule = rules.changePaths().get(path);
        String name = InputField.ETF_PRODUCT_CODE.apiName();
        if (pathRule.maxFields() > 0 && changed.size() + 1 > pathRule.maxFields()) {
            return List.of(FieldError.tooManyChanges(CHANGES));
        }
        List<FieldError> errors = new ArrayList<>();
        if (!pathRule.allows(InputField.ETF_PRODUCT_CODE)) {
            errors.add(FieldError.notChangeable(name));
        } else if (!etfProduct.isString() || !etfProducts.isAvailable(etfProduct.asString())) {
            errors.add(FieldError.notAvailable(name));
        }
        if (!changed.isEmpty()) {
            errors.addAll(validateChanged(path, changed));
        }
        return errors;
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
        putIfPresent(changed, InputField.GOAL_AMOUNT, changes.goalAmount());
        putIfPresent(changed, InputField.HELD_ASSETS, changes.heldAssets());
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
