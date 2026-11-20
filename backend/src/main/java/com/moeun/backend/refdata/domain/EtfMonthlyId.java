package com.moeun.backend.refdata.domain;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Embeddable;

/**
 * etf_monthly의 복합키.
 *
 * @param dataVersionId 버전 id
 * @param ym 해당 월의 1일
 */
@Embeddable
public record EtfMonthlyId(Long dataVersionId, LocalDate ym) implements Serializable {
}
