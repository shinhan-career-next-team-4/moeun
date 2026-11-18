package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.InputField;

/** config/rules.yml(moeun.rules) 바인딩. 입력 검증, 경로 검증, 입력 규칙 API가 함께 쓴다. */
@ConfigurationProperties("moeun.rules")
public record PlanRules(
        List<Integer> periodPresets,
        Map<InputField, FieldRule> fields,
        Map<ChangePath, PathRule> changePaths) {

    /** 값 하나를 항목 규칙에 비춰 본다. 소수 자리 → 단위 → 범위 순서로 첫 위반 코드를 돌려준다. */
    public Optional<String> check(InputField field, BigDecimal value) {
        FieldRule rule = fields.get(field);
        if (rule.scale() != null && value.stripTrailingZeros().scale() > rule.scale()) {
            return Optional.of("INVALID_SCALE");
        }
        if (rule.step() != null && value.remainder(BigDecimal.valueOf(rule.step())).signum() != 0) {
            return Optional.of("NOT_UNIT");
        }
        if (value.compareTo(rule.min()) < 0 || value.compareTo(rule.max()) > 0) {
            return Optional.of("OUT_OF_RANGE");
        }
        return Optional.empty();
    }

    public record FieldRule(boolean required, BigDecimal min, BigDecimal max, String unit, Integer scale,
            Long step) {
    }

    public record PathRule(boolean allFields, List<InputField> fields, int maxFields) {

        public boolean allows(InputField field) {
            return allFields || (fields != null && fields.contains(field));
        }
    }
}
