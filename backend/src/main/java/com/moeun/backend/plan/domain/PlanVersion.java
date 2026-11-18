package com.moeun.backend.plan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moeun.backend.plan.GoalType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 계획 조건 스냅샷. INSERT만 한다(ADR-002). 금액은 원. */
@Entity
@Table(name = "plan_version")
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type_code", nullable = false, length = 30)
    private GoalType goalType;

    @Column(name = "goal_custom_name", length = 100)
    private String goalCustomName;

    @Column(name = "goal_amount", nullable = false)
    private Long goalAmount;

    @Column(name = "target_period_months")
    private Integer targetPeriodMonths;

    @Column(name = "calc_start_month", nullable = false)
    private LocalDate calcStartMonth;

    @Column(name = "held_assets", nullable = false)
    private Long heldAssets;

    @Column(name = "monthly_saving_amount")
    private Long monthlySaving;

    @Column(name = "savings_rate_input_pct", precision = 6, scale = 3)
    private BigDecimal savingsRatePct;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "savings_ratio_pct")
    private Integer savingsRatioPct;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlanVersion() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PlanVersion(Long memberId, GoalType goalType, String goalCustomName, Long goalAmount,
            Integer targetPeriodMonths, LocalDate calcStartMonth, Long heldAssets, Long monthlySaving,
            BigDecimal savingsRatePct, Integer savingsRatioPct, String source, Instant createdAt) {
        this.memberId = memberId;
        this.goalType = goalType;
        this.goalCustomName = goalCustomName;
        this.goalAmount = goalAmount;
        this.targetPeriodMonths = targetPeriodMonths;
        this.calcStartMonth = calcStartMonth;
        this.heldAssets = heldAssets;
        this.monthlySaving = monthlySaving;
        this.savingsRatePct = savingsRatePct;
        this.savingsRatioPct = savingsRatioPct;
        this.source = source;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public GoalType getGoalType() {
        return goalType;
    }

    public String getGoalCustomName() {
        return goalCustomName;
    }

    public Long getGoalAmount() {
        return goalAmount;
    }

    public Integer getTargetPeriodMonths() {
        return targetPeriodMonths;
    }

    public LocalDate getCalcStartMonth() {
        return calcStartMonth;
    }

    public Long getHeldAssets() {
        return heldAssets;
    }

    public Long getMonthlySaving() {
        return monthlySaving;
    }

    public BigDecimal getSavingsRatePct() {
        return savingsRatePct;
    }

    public Integer getSavingsRatioPct() {
        return savingsRatioPct;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
