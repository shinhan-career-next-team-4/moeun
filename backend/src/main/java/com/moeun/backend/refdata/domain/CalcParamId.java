package com.moeun.backend.refdata.domain;

import java.io.Serializable;

import com.moeun.backend.refdata.CalcParamKey;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * calc_param의 복합키.
 *
 * @param dataVersionId 버전 id
 * @param paramKey 파라미터 항목
 */
@Embeddable
public record CalcParamId(
        Long dataVersionId,
        @Enumerated(EnumType.STRING) @Column(length = 50) CalcParamKey paramKey) implements Serializable {
}
