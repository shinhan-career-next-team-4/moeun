package com.moeun.backend.chat.application;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moeun.backend.chat.domain.HistoryMessage;

import tools.jackson.databind.JsonNode;

/**
 * ai-server POST /chat 요청 본문(ai-server ChatRequest, camelCase). text는 QUESTION에만, calcRunId는 EXPLAIN에만 있다.
 * basePlanVersionId는 QUESTION에서 질문을 접수한 순간의 계획 버전이다(#396). 없으면 키를 싣지 않는다.
 * accumulatedChanges는 QUESTION에서 그 계획 버전에 쌓인 변경 목록([{field, before, after}], #418)이다.
 * EXPLAIN에는 키를 싣지 않는다(ai-server는 null을 목록으로 받지 않는다).
 */
record AiChatRequest(long memberId, long messageId, String mode, String text, String baselineScenario,
        Long calcRunId, JsonNode planContext, List<HistoryMessage> history, Long basePlanVersionId,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode accumulatedChanges) {

    AiChatRequest(long memberId, long messageId, String mode, String text, String baselineScenario, Long calcRunId,
            JsonNode planContext, List<HistoryMessage> history, Long basePlanVersionId) {
        this(memberId, messageId, mode, text, baselineScenario, calcRunId, planContext, history, basePlanVersionId,
                null);
    }

    AiChatRequest(long memberId, long messageId, String mode, String text, String baselineScenario, Long calcRunId,
            JsonNode planContext, List<HistoryMessage> history) {
        this(memberId, messageId, mode, text, baselineScenario, calcRunId, planContext, history, null);
    }
}
