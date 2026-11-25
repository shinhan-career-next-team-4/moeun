package com.moeun.backend.chat.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.moeun.backend.chat.domain.ConditionChange;
import com.moeun.backend.plan.InputField;
import com.moeun.backend.plan.PlanView;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 대화 조건 누적(#418, D-103)의 변경 목록 다루기. 값을 계산하지 않고 고르고 비교만 한다(NFR-INT-01).
 * <ul>
 *     <li>목록을 바꾼 순서대로 접으면 지금까지 쌓인 조건의 최종 값이다. 다음 계산의 비교 기준이다</li>
 *     <li>이번 계산이 바꾼 조건은 이전 값(쌓인 값, 없으면 계획 값)과 함께 목록에 덧붙인다. 같은 값을 다시 말한 조건은 넣지 않는다</li>
 *     <li>대화로 되돌리기(#419)의 "직전 변경"은 가장 최근 메시지가 남긴 변경 전부다</li>
 * </ul>
 */
final class AccumulatedConditions {

    private AccumulatedConditions() {
    }

    /** 목록을 접은 최종 값. 같은 조건은 뒤의 변경이 이긴다. 키는 처음 바꾼 순서다. */
    static Map<String, JsonNode> fold(JsonMapper json, List<ConditionChange> changes) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (ConditionChange change : changes) {
            result.put(change.field(), json.readTree(change.afterJson()));
        }
        return result;
    }

    /** 가장 최근 메시지(직전 질문)가 남긴 변경의 메시지 id. 목록이 비었으면 null. */
    static Long lastMessageId(List<ConditionChange> changes) {
        return changes.stream().map(ConditionChange::messageId).filter(Objects::nonNull)
                .max(Long::compare).orElse(null);
    }

    /** ai-server 요청의 accumulatedChanges: [{field, before, after, messageId}] 순서대로. */
    static ArrayNode toRequest(JsonMapper json, List<ConditionChange> changes) {
        ArrayNode entries = json.createArrayNode();
        for (ConditionChange change : changes) {
            ObjectNode entry = entries.addObject();
            entry.put("field", change.field());
            if (change.messageId() != null) {
                entry.put("messageId", change.messageId());
            }
            entry.set("before", change.beforeJson() == null ? json.nullNode() : json.readTree(change.beforeJson()));
            entry.set("after", json.readTree(change.afterJson()));
        }
        return entries;
    }

    /**
     * 이번 계산이 바꾼 조건을 목록 항목으로 만든다. 이전 값은 쌓인 값, 없으면 그 계획 버전의 값이다.
     *
     * @param baseline 이번 계산의 비교 기준(지금까지 쌓인 최종 값)
     * @param turn 이번 질문에서 바꾼 조건(camelCase 키)
     */
    static List<ConditionChange> newEntries(JsonMapper json, Map<String, JsonNode> baseline,
            Map<String, JsonNode> turn, PlanView plan) {
        List<ConditionChange> entries = new ArrayList<>();
        turn.forEach((field, after) -> {
            if (after == null || after.isNull()) {
                return;
            }
            JsonNode before = baseline.containsKey(field) ? baseline.get(field) : planValue(json, plan, field);
            if (sameValue(before, after)) {
                return;
            }
            entries.add(new ConditionChange(field, before == null || before.isNull() ? null
                    : json.writeValueAsString(before), json.writeValueAsString(after)));
        });
        return entries;
    }

    /** 숫자는 표기(500000·500000.0)와 관계없이 크기로, 문자열은 글자로 비교한다. */
    private static boolean sameValue(JsonNode before, JsonNode after) {
        if (before == null || before.isNull()) {
            return false;
        }
        if (before.isNumber() && after.isNumber()) {
            return before.decimalValue().compareTo(after.decimalValue()) == 0;
        }
        return before.equals(after);
    }

    private static JsonNode planValue(JsonMapper json, PlanView plan, String field) {
        Object value = null;
        if (InputField.GOAL_AMOUNT.apiName().equals(field)) {
            value = plan.goalAmount();
        } else if (InputField.MONTHLY_SAVING_AMOUNT.apiName().equals(field)) {
            value = plan.monthlySavingAmount();
        } else if (InputField.TARGET_PERIOD_MONTHS.apiName().equals(field)) {
            value = plan.targetPeriodMonths();
        } else if (InputField.SAVINGS_RATIO_PCT.apiName().equals(field)) {
            value = plan.savingsRatioPct();
        } else if (InputField.SAVINGS_RATE_INPUT_PCT.apiName().equals(field)) {
            value = plan.savingsRateInputPct();
        } else if (InputField.ETF_PRODUCT_CODE.apiName().equals(field)) {
            value = plan.etfProductCode();
        } else if (InputField.GOAL_CUSTOM_NAME.apiName().equals(field)) {
            value = plan.goalCustomName();
        }
        return value == null ? json.nullNode() : json.valueToTree(value);
    }
}
