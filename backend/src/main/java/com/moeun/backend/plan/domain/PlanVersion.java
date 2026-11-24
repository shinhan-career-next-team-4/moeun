package com.moeun.backend.plan.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanView;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 계획 조건 스냅샷. INSERT만 한다(ADR-002). 새 버전은 아래 생성 메서드로만 만든다.
 * <ul>
 *   <li>{@link #first}: 처음 저장. 시작월은 저장한 달로 고정하고(TBD-38) ETF는 대표 상품이다(#154)</li>
 *   <li>{@link #revise}: 입력 수정. 시작월·ETF는 현재 값을 잇고 조건은 입력 그대로다</li>
 *   <li>{@link #applyOverride}: 대안·비교·대화 적용. 바뀐 조건만 덮는다(D-013)</li>
 *   <li>{@link #copyForUndo}: 되돌리기. 직전 버전을 그대로 복사한다(D-068)</li>
 * </ul>
 */
@Entity
@Table(name = "plan_version")
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Embedded
    private PlanConditions conditions;

    @Column(name = "calc_start_month", nullable = false)
    private LocalDate calcStartMonth;

    @Column(name = "etf_product_code", nullable = false, length = 20)
    private String etfProductCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private PlanSource source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlanVersion() {
    }

    private PlanVersion(Long memberId, PlanConditions conditions, LocalDate calcStartMonth, String etfProductCode,
            PlanSource source, Instant createdAt) {
        this.memberId = memberId;
        this.conditions = conditions;
        this.calcStartMonth = calcStartMonth;
        this.etfProductCode = etfProductCode;
        this.source = source;
        this.createdAt = createdAt;
    }

    public static PlanVersion first(long memberId, PlanConditions conditions, YearMonth thisMonth, Instant now) {
        return new PlanVersion(memberId, conditions, thisMonth.atDay(1), PlanView.DEFAULT_ETF_PRODUCT_CODE,
                PlanSource.INPUT, now);
    }

    /** 비중도 입력 그대로다. null이면 NULL로 저장하고 현재 버전 값을 복사하지 않는다(D-085·D-086, R-9 개정). */
    public PlanVersion revise(PlanConditions newConditions, Instant now) {
        return new PlanVersion(memberId, newConditions, calcStartMonth, etfProductCode, PlanSource.INPUT, now);
    }

    /** @param newEtfProductCode 바꾼 ETF 종목. null이면 현재 종목을 잇는다 */
    public PlanVersion applyOverride(ConditionOverride changes, String newEtfProductCode, PlanSource path,
            Instant now) {
        return new PlanVersion(memberId, conditions.overriddenBy(changes), calcStartMonth,
                newEtfProductCode != null ? newEtfProductCode : etfProductCode, path, now);
    }

    /** 경로도 원본 그대로 쓴다(schema.sql plan_version.source 주석). */
    public PlanVersion copyForUndo(Instant now) {
        return new PlanVersion(memberId, conditions, calcStartMonth, etfProductCode, source, now);
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public PlanConditions getConditions() {
        return conditions;
    }

    public LocalDate getCalcStartMonth() {
        return calcStartMonth;
    }

    public String getEtfProductCode() {
        return etfProductCode;
    }

    public PlanSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
