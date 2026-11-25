package com.moeun.backend.plan;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/** 대안·비교·대화 조건 적용 공개 서비스(계약 C12b-7-1·13). */
public interface PlanApplyService {

    /**
     * 현재 버전에 바뀐 조건만 덮어 새 버전을 만들고 현재 계획 포인터를 옮긴다. 시작월은 현재 값을 복사한다(D-013).
     *
     * @param path ALTERNATIVE·COMPARE·CHAT만 허용한다. INPUT_EDIT이면 {@link IllegalArgumentException}
     * @param conditionChanges 바꾼 조건. 키는 API와 같은 camelCase다
     * @param expectedRevision 확인창을 연 시점의 revision. 다르면 409 PLAN_CHANGED
     * @param expectedBaseVersionId 비교를 만든 기준 버전. null이면 검사하지 않는다(대안·비교 적용)
     */
    PlanApplyResult apply(long memberId, ChangePath path, Map<String, JsonNode> conditionChanges,
            long expectedRevision, Long expectedBaseVersionId);
}
