package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalcInputTest {

    private static final YearMonth START_MONTH = YearMonth.of(2026, 11);
    private static final BigDecimal RATE_PCT = new BigDecimal("3.25");

    private static CalcInput createInput(long goalAmount, Integer periodMonths, long heldAssets,
            Long monthlySavingAmount, int savingsRatioPct) {
        return new CalcInput(goalAmount, periodMonths, START_MONTH, heldAssets, monthlySavingAmount,
                RATE_PCT, savingsRatioPct, Scenario.AVG);
    }

    @Test
    @DisplayName("비중과 목표월을 입력에서 구한다")
    void derivesRatioAndTargetMonth() {
        CalcInput input = createInput(50_000_000L, 36, 5_000_000L, 1_000_000L, 50);

        assertThat(input.etfRatioPct()).isEqualTo(50);
        assertThat(input.hasEtf()).isTrue();
        assertThat(input.hasExceededGoal()).isFalse();
        assertThat(input.findTargetMonth()).contains(YearMonth.of(2029, 11));
    }

    @Test
    @DisplayName("적금 비중이 100이면 ETF가 없다")
    void hasNoEtfWhenSavingsRatioIsFull() {
        CalcInput input = createInput(50_000_000L, 36, 0L, 1_000_000L, 100);

        assertThat(input.hasEtf()).isFalse();
        assertThat(input.etfRatioPct()).isZero();
    }

    @Test
    @DisplayName("기한 미정이면 목표월이 없다")
    void hasNoTargetMonthWithoutPeriod() {
        CalcInput input = createInput(50_000_000L, null, 0L, 1_000_000L, 50);

        assertThat(input.findTargetMonth()).isEmpty();
    }

    @Test
    @DisplayName("보유 자산이 목표와 같으면 초과로 본다")
    void treatsHeldAssetsEqualToGoalAsExceeded() {
        assertThat(createInput(10_000_000L, 12, 10_000_000L, 0L, 50).hasExceededGoal()).isTrue();
        assertThat(createInput(10_000_000L, 12, 9_999_999L, 0L, 50).hasExceededGoal()).isFalse();
    }

    @Test
    @DisplayName("금액 0원과 모름은 서로 다른 입력이다")
    void distinguishesZeroFromUnknownSaving() {
        assertThat(createInput(10_000_000L, 12, 0L, 0L, 50).monthlySavingAmount()).isZero();
        assertThat(createInput(10_000_000L, 12, 0L, null, 50).monthlySavingAmount()).isNull();
    }

    @Test
    @DisplayName("계산이 성립하지 않는 값은 거부한다")
    void rejectsValuesThatCannotBeCalculated() {
        assertThatIllegalArgumentException().isThrownBy(() -> createInput(0L, 12, 0L, 0L, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> createInput(1L, 0, 0L, 0L, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> createInput(1L, 12, -1L, 0L, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> createInput(1L, 12, 0L, -1L, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> createInput(1L, 12, 0L, 0L, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> createInput(1L, 12, 0L, 0L, 101));
        assertThatIllegalArgumentException().isThrownBy(() -> new CalcInput(1L, 12, START_MONTH, 0L, 0L,
                new BigDecimal("-0.01"), 50, Scenario.AVG));
    }

    @Test
    @DisplayName("필수 값이 없으면 거부한다")
    void rejectsMissingRequiredValues() {
        assertThatNullPointerException().isThrownBy(() -> new CalcInput(1L, 12, null, 0L, 0L,
                RATE_PCT, 50, Scenario.AVG));
        assertThatNullPointerException().isThrownBy(() -> new CalcInput(1L, 12, START_MONTH, 0L, 0L,
                null, 50, Scenario.AVG));
        assertThatNullPointerException().isThrownBy(() -> new CalcInput(1L, 12, START_MONTH, 0L, 0L,
                RATE_PCT, 50, null));
    }
}
