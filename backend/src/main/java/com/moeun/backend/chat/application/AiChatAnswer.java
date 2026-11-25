package com.moeun.backend.chat.application;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * ai-server POST /chat 정상 응답(ai-server ChatResponse). conditionChanges는 ai-server의 snake_case 키 그대로다.
 * revert는 대화로 되돌리기를 실행했을 때만 있다({@code {scope: last|all|condition, condition: camelCase 키}}, #419).
 */
record AiChatAnswer(String messageType, String content, String baselineScenario, Long calcRunId,
        JsonNode conditionChanges, List<Long> citationChunkIds, JsonNode revert) {
}
