package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.InputField;

/** config/rules.yml(moeun.rules) 바인딩. 입력 검증과 경로 검증이 함께 쓴다. */
@ConfigurationProperties("moeun.rules")
public record PlanRules(
        Map<InputField, FieldRule> fields,
        Map<ChangePath, PathRule> changePaths) {

    /** 값 하나를 항목 규칙에 비춰 본다. 소수 자리 → 범위 순서로 첫 위반을 돌려준다. */
    public Optional<FieldError> check(InputField field, BigDecimal value) {
        FieldRule rule = fields.get(field);
        String name = field.apiName();
        if (rule.scale() != null && value.stripTrailingZeros().scale() > rule.scale()) {
            return Optional.of(rule.scale() == 0
                    ? FieldError.notInteger(name)
                    : FieldError.tooManyDecimals(name, rule.scale()));
        }
        if (value.compareTo(rule.min()) < 0 || value.compareTo(rule.max()) > 0) {
            return Optional.of(FieldError.outOfRange(field, rule));
        }
        return Optional.empty();
    }

    /** 글자 수 항목(목표 이름). 앞뒤 공백을 뗀 뒤 코드포인트로 센다. 비었거나 범위를 넘으면 OUT_OF_RANGE(D-074). */
    public Optional<FieldError> checkText(InputField field, String value) {
        FieldRule rule = fields.get(field);
        String trimmed = value.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < rule.min().intValue() || length > rule.max().intValue()) {
            return Optional.of(FieldError.outOfRange(field, rule));
        }
        return Optional.empty();
    }

    public record FieldRule(boolean required, BigDecimal min, BigDecimal max, String unit, Integer scale) {
    }

    public record PathRule(boolean allFields, List<InputField> fields, int maxFields) {

        public boolean allows(InputField field) {
            return allFields || (fields != null && fields.contains(field));
        }
    }
}
