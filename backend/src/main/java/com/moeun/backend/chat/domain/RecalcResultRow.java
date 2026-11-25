package com.moeun.backend.chat.domain;

/**
 * F-4·F-5가 읽는 COMPLETED RECALC_RESULT 한 행. JSON은 저장한 그대로(camelCase 키)의 객체 문자열이다.
 *
 * @param conditionChangesJson 이번 질문에서 바꾼 조건(#41)
 * @param baselineConditionChangesJson 비교 기준: 그때까지 쌓인 조건의 최종 값(#418, D-103). 없으면 null(기준 계획과 비교)
 */
public record RecalcResultRow(long id, long calcRunId, String conditionChangesJson,
        String baselineConditionChangesJson) {
}
