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

/** 버전별 ETF 종목 월말 종가 한 건. 어느 종목인지는 버전({@code data_version.product_code})이 정한다. */
@Entity
@Table(name = "etf_monthly")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EtfMonthly {

    @EmbeddedId
    private EtfMonthlyId id;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal closeValue;

    public EtfMonthly(Long dataVersionId, LocalDate ym, BigDecimal closeValue) {
        this.id = new EtfMonthlyId(dataVersionId, ym);
        this.closeValue = closeValue;
    }
}
