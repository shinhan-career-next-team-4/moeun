package com.moeun.backend.chat.application;

import java.util.List;

import com.moeun.backend.chat.domain.HistoryMessage;

import tools.jackson.databind.JsonNode;

/**
 * ai-server POST /chat 요청 본문(ai-server ChatRequest, camelCase). text는 QUESTION에만, calcRunId는 EXPLAIN에만 있다.
 * basePlanVersionId는 QUESTION에서 질문을 접수한 순간의 계획 버전이다(#396). 없으면 키를 싣지 않는다.
 */
record AiChatRequest(long memberId, long messageId, String mode, String text, String baselineScenario,
        Long calcRunId, JsonNode planContext, List<HistoryMessage> history, Long basePlanVersionId) {

    AiChatRequest(long memberId, long messageId, String mode, String text, String baselineScenario, Long calcRunId,
            JsonNode planContext, List<HistoryMessage> history) {
        this(memberId, messageId, mode, text, baselineScenario, calcRunId, planContext, history, null);
    }
}
