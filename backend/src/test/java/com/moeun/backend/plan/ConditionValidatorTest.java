package com.moeun.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ConditionValidatorTest {

    private final ConditionValidator validator = new ConditionValidator(PlanRulesFixture.load());

    @Test
    void chatCanChangeFourFields() {
        ConditionOverride changes = new ConditionOverride(1_500_000L, 48, 70, new BigDecimal("4.00"));

        assertThat(validator.validate(ChangePath.CHAT, changes)).isEmpty();
    }

    @Test
    void alternativeCanChangeOnlyOneField() {
        ConditionOverride changes = new ConditionOverride(1_500_000L, 48, null, null);

        assertThat(validator.validate(ChangePath.ALTERNATIVE, changes))
                .containsExactly(new FieldError("changes", "TOO_MANY_CHANGES"));
    }

    @Test
    void alternativeCannotChangeRate() {
        ConditionOverride changes = new ConditionOverride(null, null, null, new BigDecimal("4.00"));

        assertThat(validator.validate(ChangePath.ALTERNATIVE, changes))
                .containsExactly(new FieldError("savingsRatePct", "FIELD_NOT_CHANGEABLE"));
    }

    @Test
    void changedValuesUseSameRangesAsInput() {
        ConditionOverride changes = new ConditionOverride(10_010_000L, null, 101, null);

        assertThat(validator.validate(ChangePath.CHAT, changes)).containsExactlyInAnyOrder(
                new FieldError("monthlySaving", "OUT_OF_RANGE"),
                new FieldError("savingsRatioPct", "OUT_OF_RANGE"));
    }

    @Test
    void emptyOverrideIsRequired() {
        assertThat(validator.validate(ChangePath.COMPARE, new ConditionOverride(null, null, null, null)))
                .containsExactly(new FieldError("changes", "REQUIRED"));
        assertThat(validator.validate(ChangePath.COMPARE, null))
                .containsExactly(new FieldError("changes", "REQUIRED"));
    }
}
