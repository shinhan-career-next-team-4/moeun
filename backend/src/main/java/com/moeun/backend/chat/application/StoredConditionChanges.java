package com.moeun.backend.chat.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moeun.backend.chat.domain.RecalcResultRow;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RECALC_RESULT에 저장된 condition_changes를 키 변환 없이 읽는다(F-4 비교·F-5 적용 공통). 키는 저장된 camelCase 그대로다. */
final class StoredConditionChanges {

    private StoredConditionChanges() {
    }

    /** @throws IllegalStateException JSON이 아니거나 객체가 아닌 저장값(공통 처리기가 500 INTERNAL_ERROR로 바꾼다) */
    static Map<String, JsonNode> parse(JsonMapper json, RecalcResultRow row) {
        return parse(json, row.id(), row.conditionChangesJson());
    }

    /**
     * 비교 기준 조건(#418). 저장하지 않은 옛 행·쌓인 조건 없이 계산한 행은 빈 맵이다.
     *
     * @throws IllegalStateException JSON이 아니거나 객체가 아닌 저장값
     */
    static Map<String, JsonNode> parseBaseline(JsonMapper json, RecalcResultRow row) {
        return row.baselineConditionChangesJson() == null ? Map.of()
                : parse(json, row.id(), row.baselineConditionChangesJson());
    }

    /** 이번 변경과 비교 기준을 합친 최종 조건. 같은 키는 이번 변경이 이긴다(F-5가 새 계획으로 저장할 값, #418). */
    static Map<String, JsonNode> merged(JsonMapper json, RecalcResultRow row) {
        Map<String, JsonNode> result = new LinkedHashMap<>(parseBaseline(json, row));
        result.putAll(parse(json, row));
        return result;
    }

    /** @throws IllegalStateException JSON이 아니거나 객체가 아닌 값 */
    static Map<String, JsonNode> parse(JsonMapper json, long messageId, String changesJson) {
        JsonNode root;
        try {
            root = json.readTree(changesJson);
        } catch (JacksonException e) {
            throw new IllegalStateException("condition_changes of message " + messageId + " is not JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("condition_changes of message " + messageId + " is not an object");
        }
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(root.propertyNames());
        names.forEach(name -> changes.put(name, root.get(name)));
        return changes;
    }
}
