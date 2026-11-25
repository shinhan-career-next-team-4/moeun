package com.moeun.backend.chat.application;

import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.plan.InputField;

import tools.jackson.databind.JsonNode;

/**
 * F-4 조회 결과.
 *
 * @param messageId 조회한 RECALC_RESULT 메시지 id
 * @param applicable 지금 이 비교를 적용할 수 있는지(D-041, D-068)
 * @param changedKeys 저장된 condition_changes의 키 목록(camelCase). 순서는 보장하지 않는다
 * @param view 그 메시지의 calc_run(CHAT) 기준으로 다시 계산한 비교
 * @param goalCustomName 적용하면 바뀔 목표 이름(#345). 이름을 바꾸지 않은 비교면 null
 */
public record ChatComparison(long messageId, boolean applicable, List<String> changedKeys, ChatComparisonView view,
        String goalCustomName) {

    /** 저장된 바꾼 조건에서 목표 이름을 꺼낸다. 적용과 같게 앞뒤 공백을 뗀다. */
    static String goalCustomNameOf(Map<String, JsonNode> changes) {
        JsonNode name = changes.get(InputField.GOAL_CUSTOM_NAME.apiName());
        return name != null && name.isString() ? name.asString().strip() : null;
    }
}
