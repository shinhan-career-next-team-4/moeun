package com.moeun.backend.refdata.domain;

import java.math.BigDecimal;

import com.moeun.backend.refdata.CalcParamKey;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 버전별 계산 파라미터 값 한 건. */
@Entity
@Table(name = "calc_param")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalcParam {

    @EmbeddedId
    private CalcParamId id;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal paramValue;

    public CalcParam(Long dataVersionId, CalcParamKey paramKey, BigDecimal paramValue) {
        this.id = new CalcParamId(dataVersionId, paramKey);
        this.paramValue = paramValue;
    }
}
