package com.moeun.backend.plan.domain;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanInput;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * 계획 조건(목표·금액·기간·금리·비중). 금액은 원, 금리는 %, 비중은 적금 %. 바뀌지 않는다.
 * savingsRatioPct가 null이면 계산은 기준 데이터의 기본 비중을 쓴다(D-085·D-086).
 */
@Embeddable
public record PlanConditions(
        @Enumerated(EnumType.STRING)
        @Column(name = "goal_type_code", nullable = false, length = 30)
        GoalType goalType,

        @Column(name = "goal_custom_name", length = 100)
        String goalCustomName,

        @Column(name = "goal_amount", nullable = false)
        Long goalAmount,

        @Column(name = "target_period_months")
        Integer targetPeriodMonths,

        @Column(name = "held_assets", nullable = false)
        Long heldAssets,

        @Column(name = "monthly_saving_amount")
        Long monthlySaving,

        @Column(name = "savings_rate_input_pct", precision = 6, scale = 3)
        BigDecimal savingsRatePct,

        @JdbcTypeCode(SqlTypes.SMALLINT)
        @Column(name = "savings_ratio_pct")
        Integer savingsRatioPct) {

    /** 검증을 통과한 입력에서 만든다. 직접 입력 목표 이름은 앞뒤 공백을 떼고, 직접 입력 목표가 아니면 버린다. */
    public static PlanConditions from(PlanInput in) {
        String customName = in.goalType() == GoalType.CUSTOM && in.goalCustomName() != null
                ? in.goalCustomName().strip()
                : null;
        return new PlanConditions(in.goalType(), customName,
                toLong(in.goalAmount()),
                toInteger(in.targetPeriodMonths()),
                toLong(in.heldAssets()),
                toLong(in.monthlySavingAmount()),
                in.savingsRateInputPct(),
                toInteger(in.savingsRatioPct()));
    }

    /** 바뀐 값만 덮는다. null은 바꾸지 않음이다. */
    public PlanConditions overriddenBy(ConditionOverride changes) {
        return new PlanConditions(goalType, goalCustomName,
                firstNonNull(changes.goalAmount(), goalAmount),
                firstNonNull(changes.targetPeriodMonths(), targetPeriodMonths),
                firstNonNull(changes.heldAssets(), heldAssets),
                firstNonNull(changes.monthlySavingAmount(), monthlySaving),
                firstNonNull(changes.savingsRateInputPct(), savingsRatePct),
                firstNonNull(changes.savingsRatioPct(), savingsRatioPct));
    }

    /** 입력 검증에 넘길 형태. 적용으로 덮은 조건도 입력 화면과 같은 규칙으로 검증한다. */
    public PlanInput toInput() {
        return new PlanInput(goalType, goalCustomName,
                toDecimal(goalAmount),
                toDecimal(targetPeriodMonths),
                toDecimal(heldAssets),
                toDecimal(monthlySaving),
                savingsRatePct,
                toDecimal(savingsRatioPct));
    }

    private static Long toLong(BigDecimal value) {
        return value == null ? null : value.longValue();
    }

    private static Integer toInteger(BigDecimal value) {
        return value == null ? null : value.intValue();
    }

    private static BigDecimal toDecimal(Number value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
