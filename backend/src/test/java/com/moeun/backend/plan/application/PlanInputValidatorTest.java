package com.moeun.backend.plan.application;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.InputField;
import com.moeun.backend.plan.PlanRulesFixture;

class PlanInputValidatorTest {

    private final PlanInputValidator validator = new PlanInputValidator(PlanRulesFixture.load());

    private List<FieldError> errors(PlanInputBuilder builder) {
        return validator.validate(builder.build());
    }

    static Stream<Arguments> amountBoundaries() {
        return Stream.of(
                amount("goalAmount", 10_000L, 10_000_000_000L, PlanInputBuilder::goalAmount),
                amount("heldAssets", 0L, 10_000_000_000L, PlanInputBuilder::heldAssets),
                amount("monthlySavingAmount", 10_000L, 10_000_000L, PlanInputBuilder::monthlySavingAmount));
    }

    private static FieldError range(String field, long min, long max, String unit) {
        return new FieldError(field, "OUT_OF_RANGE", min, max, unit, null);
    }

    private static Arguments amount(String field, long min, long max,
            java.util.function.BiFunction<PlanInputBuilder, Long, PlanInputBuilder> setter) {
        Function<Long, PlanInputBuilder> withValue = v -> setter.apply(valid().goalAmount(10_000_000_000L), v);
        return Arguments.of(field, min, max, withValue);
    }

    @ParameterizedTest
    @MethodSource
    void amountBoundaries(String field, long min, long max, Function<Long, PlanInputBuilder> withValue) {
        assertThat(validator.validate(withValue.apply(min - 10_000).build()))
                .contains(range(field, min, max, "KRW"));
        assertThat(validator.validate(withValue.apply(min).build())).noneMatch(e -> e.field().equals(field));
        assertThat(validator.validate(withValue.apply(max).build())).noneMatch(e -> e.field().equals(field));
        assertThat(validator.validate(withValue.apply(max + 10_000).build()))
                .contains(range(field, min, max, "KRW"));
    }

    @Test
    void amountNotMultipleOfTenThousandIsAccepted() {
        assertThat(errors(valid().goalAmount(10_001L))).isEmpty();
    }

    @Test
    void outOfRangeCarriesMinMaxUnit() {
        assertThat(errors(valid().monthlySavingAmount(9_000L)))
                .containsExactly(
                        new FieldError("monthlySavingAmount", "OUT_OF_RANGE", 10_000L, 10_000_000L, "KRW", null));
    }

    @Test
    void decimalAmountIsNotInteger() {
        PlanRules rules = PlanRulesFixture.load();
        assertThat(rules.check(InputField.GOAL_AMOUNT, new BigDecimal("300000.5")))
                .contains(new FieldError("goalAmount", "NOT_INTEGER", null, null, null, null));
        assertThat(rules.check(InputField.GOAL_AMOUNT, new BigDecimal("300000.0"))).isEmpty();
    }

    @Test
    void periodZeroAndThreeHundredSixtyOneAreOutOfRange() {
        assertThat(errors(valid().targetPeriodMonths(0)))
                .containsExactly(range("targetPeriodMonths", 1, 360, "MONTH"));
        assertThat(errors(valid().targetPeriodMonths(361)))
                .containsExactly(range("targetPeriodMonths", 1, 360, "MONTH"));
        assertThat(errors(valid().targetPeriodMonths(360))).isEmpty();
    }

    @Test
    void rateWithThreeDecimalsIsTooManyDecimals() {
        assertThat(errors(valid().savingsRateInputPct("3.123")))
                .containsExactly(new FieldError("savingsRateInputPct", "TOO_MANY_DECIMALS", null, null, null, 2));
        assertThat(errors(valid().savingsRateInputPct("3.25"))).isEmpty();
        assertThat(errors(valid().savingsRateInputPct("3.250"))).isEmpty();
    }

    @Test
    void rateTwentyPointZeroOneIsOutOfRange() {
        assertThat(errors(valid().savingsRateInputPct("20.01")))
                .containsExactly(range("savingsRateInputPct", 0, 20, "PCT"));
        assertThat(errors(valid().savingsRateInputPct("20.00"))).isEmpty();
    }

    @Test
    void missingGoalTypeGoalAmountAndHeldAssetsAreRequired() {
        assertThat(errors(valid().goalType(null).goalAmount(null).heldAssets(null))).containsExactlyInAnyOrder(
                FieldError.required("goalType"),
                FieldError.required("goalAmount"),
                FieldError.required("heldAssets"));
    }

    @Test
    void customGoalNeedsName() {
        assertThat(errors(valid().goalType(GoalType.CUSTOM)))
                .containsExactly(FieldError.required("goalCustomName"));
    }

    @Test
    void blankCustomNameIsOutOfRange() {
        assertThat(errors(valid().goalType(GoalType.CUSTOM).goalCustomName("   ")))
                .containsExactly(range("goalCustomName", 1, 100, "CHAR"));
    }

    @Test
    void customNameCountsCodePointsAfterTrim() {
        String hundredEmoji = "😀".repeat(100);
        assertThat(errors(valid().goalType(GoalType.CUSTOM).goalCustomName(" " + hundredEmoji + " "))).isEmpty();
        assertThat(errors(valid().goalType(GoalType.CUSTOM).goalCustomName(hundredEmoji + "가")))
                .containsExactly(range("goalCustomName", 1, 100, "CHAR"));
    }

    @Test
    void nonCustomGoalIgnoresName() {
        assertThat(errors(valid().goalCustomName("   "))).isEmpty();
    }

    @Test
    void periodAndMonthlySavingAreBothRequired() {
        assertThat(validator.validate(valid().targetPeriodMonths(null).build()))
                .containsExactly(FieldError.required("targetPeriodMonths"));
        assertThat(validator.validate(valid().monthlySavingAmount(null).build()))
                .containsExactly(FieldError.required("monthlySavingAmount"));
    }

    @Test
    void heldAssetsAboveGoalIsNotAnError() {
        assertThat(errors(valid().goalAmount(10_000_000L).heldAssets(20_000_000L))).isEmpty();
    }
}
