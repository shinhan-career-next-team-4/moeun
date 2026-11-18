package com.moeun.backend.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** 입력 화면 값 전체(계약 C12a-5-1). 금액은 원, 숫자 필드는 소수 절삭 방지를 위해 JsonNode로 받는다. */
public record PlanInput(
        GoalType goalType,
        String goalCustomName,
        Map<String, JsonNode> fields) {

    public PlanInput {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
