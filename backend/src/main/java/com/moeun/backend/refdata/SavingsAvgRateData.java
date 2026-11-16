package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 적금 평균금리(FR-DATA-01). 사용자가 금리를 비웠을 때 기본 금리로 쓴다.
 *
 * @param version 이 값이 속한 버전
 * @param baseMonth 금리 기준월
 * @param avgRatePct 평균금리(연, 세전, %)
 */
public record SavingsAvgRateData(DataVersionInfo version, YearMonth baseMonth, BigDecimal avgRatePct) {
}
