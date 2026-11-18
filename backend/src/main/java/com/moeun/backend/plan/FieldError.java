package com.moeun.backend.plan;

/**
 * 검증 오류 하나. field는 API의 camelCase 필드명(또는 "changes"), code는 아래 중 하나.
 * REQUIRED, OUT_OF_RANGE, NOT_UNIT, INVALID_SCALE, TOO_LONG, BLANK, ONE_OF_PERIOD_OR_SAVING,
 * EXISTING_EXCEEDS_SAVING, RATIO_CHOICE_REQUIRED, FIELD_NOT_CHANGEABLE, TOO_MANY_CHANGES
 */
public record FieldError(String field, String code) {
}
