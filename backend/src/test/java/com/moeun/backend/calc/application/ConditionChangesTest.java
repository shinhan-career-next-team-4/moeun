package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.plan.ConditionOverride;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 대화로 바꾼 조건의 타입 변환(D-040, CP-07). {@code /internal/calc}와 대화 기록 재계산이 같이 쓴다. */
class ConditionChangesTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static ObjectNode object() {
        return NODES.objectNode();
    }

    @Test
    @DisplayName("null 값 키는 빼고 나머지는 요청에 적힌 순서를 지킨다")
    void dropsNullsAndKeepsOrder() {
        ObjectNode changes = object();
        changes.put("targetPeriodMonths", 24);
        changes.putNull("savingsRatioPct");
        changes.put("monthlySavingAmount", 500_000);

        Map<String, JsonNode> result = ConditionChanges.withoutNulls(changes);

        assertThat(result.keySet()).containsExactly("targetPeriodMonths", "monthlySavingAmount");
    }

    @Test
    @DisplayName("허용 6개(목표 금액·월 저축액·비중·적금 금리·기간·ETF 종목) 밖의 키만 골라 요청 순서로 돌려준다")
    void findsUnchangeableKeys() {
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changes.put("goalAmount", NODES.numberNode(1));
        changes.put("monthlySavingAmount", NODES.numberNode(1));
        changes.put("heldAssets", NODES.numberNode(1));

        assertThat(ConditionChanges.unchangeableKeys(changes)).containsExactly("heldAssets");
        assertThat(ConditionChanges.CHANGEABLE_KEYS).containsExactlyInAnyOrder("goalAmount", "monthlySavingAmount",
                "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths", "etfProductCode");
    }

    @Test
    @DisplayName("목표 금액도 정수로 바꾼다(D-092 1단계)")
    void convertsGoalAmount() {
        ConditionChanges.Conversion conversion = ConditionChanges.convert(
                Map.of("goalAmount", NODES.numberNode(25_000_000)));

        assertThat(conversion.override().goalAmount()).isEqualTo(25_000_000L);
        assertThat(conversion.isExact()).isTrue();
    }

    @Test
    @DisplayName("값이 모두 유한한 JSON 숫자인지 본다. 문자열·무한대는 아니다")
    void checksFiniteNumbers() {
        assertThat(ConditionChanges.allValuesWellTyped(Map.of("monthlySavingAmount", NODES.numberNode(1)))).isTrue();
        assertThat(ConditionChanges.allValuesWellTyped(Map.of("monthlySavingAmount", NODES.stringNode("1"))))
                .isFalse();
        assertThat(ConditionChanges.allValuesWellTyped(
                Map.of("savingsRateInputPct", NODES.numberNode(Double.POSITIVE_INFINITY)))).isFalse();
    }

    @Test
    @DisplayName("정수 자리는 정수로, 금리는 소수 그대로 바꾼다. 500000.0처럼 값이 정수면 받는다")
    void convertsValues() {
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changes.put("monthlySavingAmount", NODES.numberNode(new BigDecimal("500000.0")));
        changes.put("targetPeriodMonths", NODES.numberNode(24));
        changes.put("savingsRatioPct", NODES.numberNode(30));
        changes.put("savingsRateInputPct", NODES.numberNode(new BigDecimal("3.125")));

        ConditionChanges.Conversion conversion = ConditionChanges.convert(changes);

        assertThat(conversion.override())
                .isEqualTo(new ConditionOverride(500_000L, 24, 30, new BigDecimal("3.125"), null, null));
        assertThat(conversion.notIntegerKeys()).isEmpty();
        assertThat(conversion.outOfTypeRangeKeys()).isEmpty();
        assertThat(conversion.isExact()).isTrue();
    }

    @Test
    @DisplayName("없는 키는 null(바꾸지 않음)이다")
    void leavesMissingKeysNull() {
        ConditionChanges.Conversion conversion = ConditionChanges.convert(
                Map.of("monthlySavingAmount", NODES.numberNode(500_000)));

        assertThat(conversion.override()).isEqualTo(new ConditionOverride(500_000L, null, null, null, null, null));
    }

    @Test
    @DisplayName("정수 자리의 소수는 잘라서 통과시키지 않고 그 키를 알리며 값은 null이다")
    void reportsDecimalInIntegerField() {
        ConditionChanges.Conversion conversion = ConditionChanges.convert(
                Map.of("targetPeriodMonths", NODES.numberNode(new BigDecimal("24.5"))));

        assertThat(conversion.notIntegerKeys()).containsExactly("targetPeriodMonths");
        assertThat(conversion.override().targetPeriodMonths()).isNull();
        assertThat(conversion.isExact()).isFalse();
    }

    @Test
    @DisplayName("타입 범위를 넘는 정수는 경계값으로 넘기고 그 키를 알린다(범위 검사가 OUT_OF_RANGE로 잡게)")
    void clampsValuesBeyondTypeRange() {
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changes.put("targetPeriodMonths", NODES.numberNode(new BigDecimal("99999999999")));
        changes.put("monthlySavingAmount", NODES.numberNode(new BigDecimal("-1e30")));

        ConditionChanges.Conversion conversion = ConditionChanges.convert(changes);

        assertThat(conversion.override().targetPeriodMonths()).isEqualTo(Integer.MAX_VALUE);
        assertThat(conversion.override().monthlySavingAmount()).isEqualTo(Long.MIN_VALUE);
        assertThat(conversion.outOfTypeRangeKeys()).containsExactly("targetPeriodMonths", "monthlySavingAmount");
        assertThat(conversion.isExact()).isFalse();
    }

    @Test
    @DisplayName("ETF 종목 코드만 문자열이어야 하고, 숫자·불리언이면 타입이 맞지 않는다(D-097)")
    void checksEtfProductCodeType() {
        assertThat(ConditionChanges.allValuesWellTyped(Map.of("etfProductCode", NODES.stringNode("069500"))))
                .isTrue();
        assertThat(ConditionChanges.allValuesWellTyped(Map.of("etfProductCode", NODES.numberNode(133690))))
                .isFalse();
        assertThat(ConditionChanges.allValuesWellTyped(Map.of("etfProductCode", NODES.booleanNode(true))))
                .isFalse();
    }

    @Test
    @DisplayName("ETF 종목 코드는 바꾼 조건(override) 밖에 문자열 그대로 돌려주고, 있는 종목인지는 보지 않는다")
    void convertsEtfProductCode() {
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changes.put("etfProductCode", NODES.stringNode("999999"));
        changes.put("monthlySavingAmount", NODES.numberNode(500_000));

        ConditionChanges.Conversion conversion = ConditionChanges.convert(changes);

        assertThat(conversion.etfProductCode()).isEqualTo("999999");
        assertThat(conversion.override().monthlySavingAmount()).isEqualTo(500_000L);
        assertThat(conversion.isExact()).isTrue();
    }
}
