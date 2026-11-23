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
    void chatAndAlternativeCannotChangeEtfProductYet() {
        // 대화 경로는 D-040 개정(#160) 뒤에 연다
        assertThat(validator.validateChanges(ChangePath.CHAT,
                productChange(JsonNodeFactory.instance.stringNode("069500"))))
                .containsExactly(FieldError.notChangeable("etfProductCode"));
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
}
