package com.moeun.backend.refdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 버전별 S&P500 월 종가 한 건. */
@Entity
@Table(name = "sp500_monthly")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sp500Monthly {

    @EmbeddedId
    private Sp500MonthlyId id;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal closeValue;

    // 분배금 제외 지수로 계산하기로 해서 지금은 채우지 않는다(TBD-02)
    @Column(precision = 18, scale = 6)
    private BigDecimal totalReturnIndex;

    public Sp500Monthly(Long dataVersionId, LocalDate ym, BigDecimal closeValue) {
        this.id = new Sp500MonthlyId(dataVersionId, ym);
        this.closeValue = closeValue;
    }
}
