package com.moeun.backend.chat.domain;

/**
 * 대화 조건 누적(#418, D-103)의 변경 목록 한 항목. 값은 JSON 문자열 그대로다(숫자 조건은 숫자, 종목 코드·목표 이름은 문자열).
 *
 * @param field camelCase 조건 키(/internal/calc conditionChanges와 같은 7개)
 * @param beforeJson 바꾸기 전 값. 계획에 값이 없었으면 null
 * @param afterJson 바꾼 값
 * @param messageId 그 변경을 계산한 RECALC_RESULT. "직전 변경 취소"(#419)는 가장 최근 메시지의 변경 전부다. 저장 전이면 null
 */
public record ConditionChange(String field, String beforeJson, String afterJson, Long messageId) {

    /** 저장 전의 새 항목. 메시지는 저장할 때 정한다. */
    public ConditionChange(String field, String beforeJson, String afterJson) {
        this(field, beforeJson, afterJson, null);
    }
}
