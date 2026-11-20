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
        JsonNode root;
        try {
            root = json.readTree(row.conditionChangesJson());
        } catch (JacksonException e) {
            throw new IllegalStateException("condition_changes of message " + row.id() + " is not JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("condition_changes of message " + row.id() + " is not an object");
        }
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(root.propertyNames());
        names.forEach(name -> changes.put(name, root.get(name)));
        return changes;
    }
}
