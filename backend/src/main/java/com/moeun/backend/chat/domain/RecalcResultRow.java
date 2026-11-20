package com.moeun.backend.chat.domain;

/** F-4가 읽는 COMPLETED RECALC_RESULT 한 행. conditionChangesJson은 #41이 저장한 그대로(camelCase 키)의 JSON 객체 문자열이다. */
public record RecalcResultRow(long id, long calcRunId, String conditionChangesJson) {
}
