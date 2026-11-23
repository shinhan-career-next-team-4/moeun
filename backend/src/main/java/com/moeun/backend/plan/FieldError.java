package com.moeun.backend.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moeun.backend.plan.application.PlanRules.FieldRule;

/**
 * 검증 오류 하나(계약 C12a-5-4). field는 요청 키 이름(camelCase, 또는 "conditionChanges").
 * reason은 REQUIRED, OUT_OF_RANGE, NOT_INTEGER, TOO_MANY_DECIMALS 중 하나이고 조건 변경 경로에서만
 * NOT_CHANGEABLE, TOO_MANY_CHANGES, NOT_AVAILABLE(고를 수 없는 ETF 종목, #154)이 더 나온다.
 * OUT_OF_RANGE만 min·max·unit을, TOO_MANY_DECIMALS만 scale을 싣고 나머지는 null이다. 단 대화 경로의 ETF 종목처럼
 * 목록에 없는 값의 OUT_OF_RANGE({@link #notInChoices})는 범위가 아니라서 min·max·unit도 없다(D-097).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldError(String field, String reason, Long min, Long max, String unit, Integer scale) {

    public static FieldError required(String field) {
        return of(field, "REQUIRED");
    }

    public static FieldError notChangeable(String field) {
        return of(field, "NOT_CHANGEABLE");
    }

    /** 데이터를 적재하지 않았거나 종목 코드가 아닌 값(#154). */
    public static FieldError notAvailable(String field) {
        return of(field, "NOT_AVAILABLE");
    }

    /** 고를 수 있는 목록에 없는 값. 범위가 아니라 목록이라 min·max가 없다(대화 경로의 ETF 종목, D-097). */
    public static FieldError notInChoices(String field) {
        return of(field, "OUT_OF_RANGE");
    }

    public static FieldError tooManyChanges(String field) {
        return of(field, "TOO_MANY_CHANGES");
    }

    private static FieldError of(String field, String reason) {
        return new FieldError(field, reason, null, null, null, null);
    }

    public static FieldError outOfRange(InputField field, FieldRule rule) {
        return new FieldError(field.apiName(), "OUT_OF_RANGE", rule.min().longValueExact(),
                rule.max().longValueExact(), rule.unit(), null);
    }

    public static FieldError notInteger(String field) {
        return of(field, "NOT_INTEGER");
    }

    public static FieldError tooManyDecimals(String field, int scale) {
        return new FieldError(field, "TOO_MANY_DECIMALS", null, null, null, scale);
    }
}
