package com.moeun.backend.plan.application;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanRulesFixture;

class PlanInputValidatorTest {

    private final PlanInputValidator validator = new PlanInputValidator(PlanRulesFixture.load());

    private List<FieldError> errors(PlanInputBuilder builder) {
        return validator.validate(builder.build());
    }

    static Stream<Arguments> amountBoundaries() {
        return Stream.of(
                amount("goalAmount", 10_000L, 1_000_000_000L, PlanInputBuilder::goalAmount),
                amount("heldAssets", 0L, 1_000_000_000L, PlanInputBuilder::heldAssets),
                amount("monthlySavingAmount", 10_000L, 10_000_000L, PlanInputBuilder::monthlySavingAmount));
    }

    private static Arguments amount(String field, long min, long max,
            java.util.function.BiFunction<PlanInputBuilder, Long, PlanInputBuilder> setter) {
        Function<Long, PlanInputBuilder> withValue = v -> setter.apply(valid().goalAmount(1_000_000_000L), v);
        return Arguments.of(field, min, max, withValue);
    }

    @ParameterizedTest
    @MethodSource
    void amountBoundaries(String field, long min, long max, Function<Long, PlanInputBuilder> withValue) {
        assertThat(validator.validate(withValue.apply(min - 10_000).build()))
                .contains(new FieldError(field, "OUT_OF_RANGE"));
        assertThat(validator.validate(withValue.apply(min).build())).noneMatch(e -> e.field().equals(field));
        assertThat(validator.validate(withValue.apply(max).build())).noneMatch(e -> e.field().equals(field));
        assertThat(validator.validate(withValue.apply(max + 10_000).build()))
                .contains(new FieldError(field, "OUT_OF_RANGE"));
    }

    @Test
    void amountNotMultipleOfTenThousandIsNotUnit() {
        assertThat(errors(valid().goalAmount(10_001L))).containsExactly(new FieldError("goalAmount", "NOT_UNIT"));
    }

    @Test
    void periodZeroAndSixHundredOneAreOutOfRange() {
        assertThat(errors(valid().targetPeriodMonths(0)))
                .containsExactly(new FieldError("targetPeriodMonths", "OUT_OF_RANGE"));
        assertThat(errors(valid().targetPeriodMonths(601)))
                .containsExactly(new FieldError("targetPeriodMonths", "OUT_OF_RANGE"));
        assertThat(errors(valid().targetPeriodMonths(600))).isEmpty();
    }

    @Test
    void rateWithThreeDecimalsIsInvalidScale() {
        assertThat(errors(valid().savingsRateInputPct("3.255")))
                .containsExactly(new FieldError("savingsRateInputPct", "INVALID_SCALE"));
        assertThat(errors(valid().savingsRateInputPct("3.25"))).isEmpty();
        assertThat(errors(valid().savingsRateInputPct("3.250"))).isEmpty();
    }

    @Test
    void rateFiftyPointZeroOneIsOutOfRange() {
        assertThat(errors(valid().savingsRateInputPct("50.01")))
                .containsExactly(new FieldError("savingsRateInputPct", "OUT_OF_RANGE"));
        assertThat(errors(valid().savingsRateInputPct("50.00"))).isEmpty();
    }

    @Test
    void missingGoalTypeGoalAmountAndHeldAssetsAreRequired() {
        assertThat(errors(valid().goalType(null).goalAmount(null).heldAssets(null))).containsExactlyInAnyOrder(
                new FieldError("goalType", "REQUIRED"),
                new FieldError("goalAmount", "REQUIRED"),
                new FieldError("heldAssets", "REQUIRED"));
    }

    @Test
    void customGoalNeedsName() {
        assertThat(errors(valid().goalType(GoalType.CUSTOM)))
                .containsExactly(new FieldError("goalCustomName", "REQUIRED"));
    }

    @Test
    void blankCustomNameIsBlank() {
        assertThat(errors(valid().goalType(GoalType.CUSTOM).goalCustomName("   ")))
                .containsExactly(new FieldError("goalCustomName", "BLANK"));
    }

    @Test
    void customNameCountsCodePointsAfterTrim() {
        String hundredEmoji = "😀".repeat(100);
        assertThat(errors(valid().goalType(GoalType.CUSTOM).goalCustomName(" " + hundredEmoji + " "))).isEmpty();
        assertThat(errors(valid().goalType(GoalType.CUSTOM).goalCustomName(hundredEmoji + "가")))
                .containsExactly(new FieldError("goalCustomName", "TOO_LONG"));
    }

    @Test
    void nonCustomGoalIgnoresName() {
        assertThat(errors(valid().goalCustomName("   "))).isEmpty();
    }

    @Test
    void periodAndMonthlySavingAreBothRequired() {
        assertThat(validator.validate(valid().targetPeriodMonths(null).build()))
                .containsExactly(new FieldError("targetPeriodMonths", "REQUIRED"));
        assertThat(validator.validate(valid().monthlySavingAmount(null).build()))
                .containsExactly(new FieldError("monthlySavingAmount", "REQUIRED"));
    }

    @Test
    void heldAssetsAboveGoalIsNotAnError() {
        assertThat(errors(valid().goalAmount(10_000_000L).heldAssets(20_000_000L))).isEmpty();
    }
}
