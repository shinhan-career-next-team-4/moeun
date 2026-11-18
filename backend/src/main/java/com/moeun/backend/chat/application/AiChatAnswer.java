package com.moeun.backend.chat.application;

import java.util.List;

import tools.jackson.databind.JsonNode;

/** ai-server POST /chat 정상 응답(ai-server ChatResponse). conditionChanges는 ai-server의 snake_case 키 그대로다. */
record AiChatAnswer(String messageType, String content, String baselineScenario, Long calcRunId,
        JsonNode conditionChanges, List<Long> citationChunkIds) {
}
