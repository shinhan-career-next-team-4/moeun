package com.moeun.backend.chat.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.moeun.backend.chat.domain.MessageType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ai-server POST /chat 호출. 호출이 어떤 이유로든 답을 못 주면(연결·시간 초과, 4xx·5xx, {@code {error}} 본문,
 * 알 수 없는 형식) 예외를 던진다. 트랜잭션 밖에서 부른다.
 */
@Component
class AiChatClient {

    private static final String BAD_OUTPUT = "BAD_OUTPUT";

    private final RestClient client;
    private final JsonMapper json;

    AiChatClient(RestClient aiServerRestClient, JsonMapper json) {
        this.client = aiServerRestClient;
        this.json = json;
    }

    AiChatAnswer chat(AiChatRequest request) {
        byte[] body = client.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(request))
                .retrieve()
                .body(byte[].class);
        if (body == null) {
            throw new AiServerException("빈 응답");
        }
        return parse(json.readTree(body));
    }

    private static AiChatAnswer parse(JsonNode node) {
        if (!node.isObject()) {
            throw new AiServerException("객체가 아닌 응답");
        }
        if (node.has("error")) {
            // 상태 코드와 관계없이 {error: CODE} 본문은 실패다(BAD_OUTPUT 포함)
            String code = node.get("error").asString();
            throw new AiServerException(BAD_OUTPUT.equals(code) ? BAD_OUTPUT : "error=" + code);
        }
        String messageType = text(node, "messageType");
        String content = text(node, "content");
        if (!MessageType.isAnswerName(messageType)) {
            throw new AiServerException("알 수 없는 messageType: " + messageType);
        }
        if (content == null || content.isBlank()) {
            throw new AiServerException("content가 비어 있음");
        }
        JsonNode changes = node.get("conditionChanges");
        if (changes != null && !changes.isNull() && !changes.isObject()) {
            throw new AiServerException("conditionChanges가 객체가 아님");
        }
        JsonNode revert = node.get("revert");
        if (revert != null && !revert.isNull() && !revert.isObject()) {
            throw new AiServerException("revert가 객체가 아님");
        }
        return new AiChatAnswer(messageType, content, text(node, "baselineScenario"), longValue(node, "calcRunId"),
                changes == null || changes.isNull() ? null : changes, citationChunkIds(node.get("citationChunkIds")),
                revert == null || revert.isNull() ? null : revert);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    /** 중복 chunk는 한 번만 저장한다(같은 답에 같은 출처를 두 번 달 수 없다). */
    private static List<Long> citationChunkIds(JsonNode ids) {
        if (ids == null || ids.isNull()) {
            return List.of();
        }
        if (!ids.isArray()) {
            throw new AiServerException("citationChunkIds가 배열이 아님");
        }
        Set<Long> unique = new LinkedHashSet<>();
        ids.forEach(id -> unique.add(id.asLong()));
        return new ArrayList<>(unique);
    }
}
