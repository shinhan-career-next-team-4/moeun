package com.moeun.backend.refdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 버전당 한 건인 적금 평균금리. */
@Entity
@Table(name = "savings_avg_rate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavingsAvgRate {

    @Id
    private Long dataVersionId;

    // 매월 1일만 허용한다(ck_sar_month_first)
    @Column(nullable = false)
    private LocalDate baseMonth;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal avgRatePct;

    public SavingsAvgRate(Long dataVersionId, LocalDate baseMonth, BigDecimal avgRatePct) {
        this.dataVersionId = dataVersionId;
        this.baseMonth = baseMonth;
        this.avgRatePct = avgRatePct;
    }
}
