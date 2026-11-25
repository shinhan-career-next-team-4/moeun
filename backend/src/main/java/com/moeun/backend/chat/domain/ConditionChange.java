package com.moeun.backend.chat.domain;

/**
 * 대화 조건 누적(#418, D-103)의 변경 목록 한 항목. 값은 JSON 문자열 그대로다(숫자 조건은 숫자, 종목 코드·목표 이름은 문자열).
 *
 * @param field camelCase 조건 키(/internal/calc conditionChanges와 같은 7개)
 * @param beforeJson 바꾸기 전 값. 계획에 값이 없었으면 null
 * @param afterJson 바꾼 값
 */
public record ConditionChange(String field, String beforeJson, String afterJson) {
}
