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
                .containsExactly(FieldError.tooManyChanges("changes"));
    }

    @Test
    void alternativeCannotChangeRate() {
        ConditionOverride changes = new ConditionOverride(null, null, null, new BigDecimal("4.00"));

        assertThat(validator.validate(ChangePath.ALTERNATIVE, changes))
                .containsExactly(FieldError.notChangeable("savingsRateInputPct"));
    }

    @Test
    void changedValuesUseSameRangesAsInput() {
        ConditionOverride changes = new ConditionOverride(10_010_000L, null, 101, null);

        assertThat(validator.validate(ChangePath.CHAT, changes)).containsExactlyInAnyOrder(
                new FieldError("monthlySavingAmount", "OUT_OF_RANGE", 10_000L, 10_000_000L, "KRW", null),
                new FieldError("savingsRatioPct", "OUT_OF_RANGE", 0L, 100L, "PCT", null));
    }

    @Test
    void emptyOverrideIsRequired() {
        assertThat(validator.validate(ChangePath.COMPARE, new ConditionOverride(null, null, null, null)))
                .containsExactly(FieldError.required("changes"));
        assertThat(validator.validate(ChangePath.COMPARE, null))
                .containsExactly(FieldError.required("changes"));
    }
}
