package com.moeun.backend.chat.application;

import java.util.List;

import com.moeun.backend.chat.domain.HistoryMessage;

import tools.jackson.databind.JsonNode;

/** ai-server POST /chat 요청 본문(ai-server ChatRequest, camelCase). text는 QUESTION에만, calcRunId는 EXPLAIN에만 있다. */
record AiChatRequest(long memberId, long messageId, String mode, String text, String baselineScenario,
        Long calcRunId, JsonNode planContext, List<HistoryMessage> history) {
}
