package com.moeun.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class ConditionValidatorTest {

    /** 데이터를 적재한 종목만 고를 수 있다(#154). 실제 앱에서는 calc가 refdata로 판단한다. */
    private final ConditionValidator validator = new ConditionValidator(PlanRulesFixture.load(),
            LoadedEtfProductsConfig.LOADED::contains);

    private static Map<String, JsonNode> productChange(JsonNode code) {
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changes.put("etfProductCode", code);
        return changes;
    }

    @Test
    void compareCanChangeEtfProductToLoadedProduct() {
        assertThat(validator.validateChanges(ChangePath.COMPARE,
                productChange(JsonNodeFactory.instance.stringNode("069500")))).isEmpty();
    }

    @Test
    void compareRejectsEtfProductWithoutData() {
        assertThat(validator.validateChanges(ChangePath.COMPARE,
                productChange(JsonNodeFactory.instance.stringNode("999999"))))
                .containsExactly(FieldError.notAvailable("etfProductCode"));
    }

    @Test
    void etfProductCodeMustBeText() {
        assertThat(validator.validateChanges(ChangePath.COMPARE,
                productChange(JsonNodeFactory.instance.numberNode(69500))))
                .containsExactly(FieldError.notAvailable("etfProductCode"));
    }

    @Test
    void etfProductCountsTowardChangedFieldsWithOthers() {
        Map<String, JsonNode> changes = productChange(JsonNodeFactory.instance.stringNode("133690"));
        changes.put("monthlySavingAmount", JsonNodeFactory.instance.numberNode(400_000L));

        assertThat(validator.validateChanges(ChangePath.COMPARE, changes)).isEmpty();
    }

    @Test
    void reportsEtfProductAndNumberErrorsTogether() {
        Map<String, JsonNode> changes = productChange(JsonNodeFactory.instance.stringNode("999999"));
        changes.put("monthlySavingAmount", JsonNodeFactory.instance.numberNode(5_000L));

        assertThat(validator.validateChanges(ChangePath.COMPARE, changes)).containsExactly(
                FieldError.notAvailable("etfProductCode"),
                new FieldError("monthlySavingAmount", "OUT_OF_RANGE", 10_000L, 10_000_000L, "KRW", null));
    }

    @Test
    void nullEtfProductIsNoChangeAndEmptyIsNotAvailable() {
        assertThat(validator.validateChanges(ChangePath.COMPARE, productChange(JsonNodeFactory.instance.nullNode())))
                .containsExactly(FieldError.required("conditionChanges"));
        assertThat(validator.validateChanges(ChangePath.COMPARE,
                productChange(JsonNodeFactory.instance.stringNode(""))))
                .containsExactly(FieldError.notAvailable("etfProductCode"));
    }

    @Test
    void chatCanChangeEtfProductButAlternativeCannot() {
        // 대화 경로도 결과 화면과 같은 기준으로 종목을 바꾼다(D-097, #154)
        assertThat(validator.validateChanges(ChangePath.CHAT,
                productChange(JsonNodeFactory.instance.stringNode("069500")))).isEmpty();
        assertThat(validator.validateChanges(ChangePath.CHAT,
                productChange(JsonNodeFactory.instance.stringNode("999999"))))
                .containsExactly(FieldError.notAvailable("etfProductCode"));
        assertThat(validator.validateChanges(ChangePath.ALTERNATIVE,
                productChange(JsonNodeFactory.instance.stringNode("069500"))))
                .containsExactly(FieldError.notChangeable("etfProductCode"));
    }

    @Test
    void chatCanChangeFiveFields() {
        ConditionOverride changes = new ConditionOverride(1_500_000L, 48, 70, new BigDecimal("4.00"),
                50_000_000L, null);

        assertThat(validator.validate(ChangePath.CHAT, changes)).isEmpty();
    }

    @Test
    void chatCannotChangeHeldAssetsInPhaseOne() {
        ConditionOverride changes = new ConditionOverride(null, null, null, null, null, 10_000_000L);

        assertThat(validator.validate(ChangePath.CHAT, changes))
                .containsExactly(FieldError.notChangeable("heldAssets"));
    }

    @Test
    void alternativeCanChangeOnlyOneField() {
        ConditionOverride changes = new ConditionOverride(1_500_000L, 48, null, null, null, null);

        assertThat(validator.validate(ChangePath.ALTERNATIVE, changes))
                .containsExactly(FieldError.tooManyChanges("conditionChanges"));
    }

    @Test
    void alternativeCannotChangeRate() {
        ConditionOverride changes = new ConditionOverride(null, null, null, new BigDecimal("4.00"), null, null);

        assertThat(validator.validate(ChangePath.ALTERNATIVE, changes))
                .containsExactly(FieldError.notChangeable("savingsRateInputPct"));
    }

    @Test
    void changedValuesUseSameRangesAsInput() {
        ConditionOverride changes = new ConditionOverride(10_010_000L, null, 101, null, null, null);

        assertThat(validator.validate(ChangePath.CHAT, changes)).containsExactlyInAnyOrder(
                new FieldError("monthlySavingAmount", "OUT_OF_RANGE", 10_000L, 10_000_000L, "KRW", null),
                new FieldError("savingsRatioPct", "OUT_OF_RANGE", 0L, 100L, "PCT", null));
    }

    @Test
    void emptyOverrideIsRequired() {
        assertThat(validator.validate(ChangePath.COMPARE, new ConditionOverride(null, null, null, null, null, null)))
                .containsExactly(FieldError.required("conditionChanges"));
        assertThat(validator.validate(ChangePath.COMPARE, null))
                .containsExactly(FieldError.required("conditionChanges"));
    }

    private static Map<String, JsonNode> nameChange(JsonNode name) {
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changes.put("goalCustomName", name);
        return changes;
    }

    private static final FieldError NAME_OUT_OF_RANGE = new FieldError("goalCustomName", "OUT_OF_RANGE", 1L, 100L,
            "CHAR", null);

    @Test
    void chatCanChangeGoalNameAloneButOtherPathsCannot() {
        // 대화로 목표 이름을 바꿔 적용한다(#345). 결과 화면 비교·대안은 이름을 바꾸지 않는다
        JsonNode name = JsonNodeFactory.instance.stringNode("가게 보증금");
        assertThat(validator.validateChanges(ChangePath.CHAT, nameChange(name))).isEmpty();
        assertThat(validator.validateChanges(ChangePath.COMPARE, nameChange(name)))
                .containsExactly(FieldError.notChangeable("goalCustomName"));
        assertThat(validator.validateChanges(ChangePath.ALTERNATIVE, nameChange(name)))
                .containsExactly(FieldError.notChangeable("goalCustomName"));
    }

    @Test
    void goalNameDoesNotCountTowardChangedFields() {
        Map<String, JsonNode> changes = nameChange(JsonNodeFactory.instance.stringNode("가게 보증금"));
        changes.put("goalAmount", JsonNodeFactory.instance.numberNode(50_000_000L));
        changes.put("monthlySavingAmount", JsonNodeFactory.instance.numberNode(400_000L));
        changes.put("targetPeriodMonths", JsonNodeFactory.instance.numberNode(48));
        changes.put("savingsRatioPct", JsonNodeFactory.instance.numberNode(70));
        changes.put("savingsRateInputPct", JsonNodeFactory.instance.numberNode(new BigDecimal("4.00")));
        changes.put("etfProductCode", JsonNodeFactory.instance.stringNode("069500"));

        assertThat(validator.validateChanges(ChangePath.CHAT, changes)).isEmpty();
    }

    @Test
    void goalNameUsesSameLengthRuleAsInput() {
        // 앞뒤 공백을 뗀 1~100자(D-074). 문자열이 아니어도 이름으로 쓸 수 없는 값이다
        assertThat(validator.validateChanges(ChangePath.CHAT,
                nameChange(JsonNodeFactory.instance.stringNode("   ")))).containsExactly(NAME_OUT_OF_RANGE);
        assertThat(validator.validateChanges(ChangePath.CHAT,
                nameChange(JsonNodeFactory.instance.stringNode("가".repeat(101))))).containsExactly(NAME_OUT_OF_RANGE);
        assertThat(validator.validateChanges(ChangePath.CHAT,
                nameChange(JsonNodeFactory.instance.stringNode(" " + "가".repeat(100) + " ")))).isEmpty();
        assertThat(validator.validateChanges(ChangePath.CHAT,
                nameChange(JsonNodeFactory.instance.numberNode(1)))).containsExactly(NAME_OUT_OF_RANGE);
    }

    @Test
    void reportsGoalNameAndNumberErrorsTogether() {
        Map<String, JsonNode> changes = nameChange(JsonNodeFactory.instance.stringNode(""));
        changes.put("monthlySavingAmount", JsonNodeFactory.instance.numberNode(5_000L));

        assertThat(validator.validateChanges(ChangePath.CHAT, changes)).containsExactly(NAME_OUT_OF_RANGE,
                new FieldError("monthlySavingAmount", "OUT_OF_RANGE", 10_000L, 10_000_000L, "KRW", null));
    }
}
