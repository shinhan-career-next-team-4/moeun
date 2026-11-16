package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 계산 파라미터 한 벌(CP-03).
 *
 * @param version 이 값이 속한 버전
 * @param values 항목별 값
 */
public record CalcParams(DataVersionInfo version, Map<CalcParamKey, BigDecimal> values) {

    public CalcParams {
        values = Map.copyOf(values);
    }

    /** 항목 값을 꺼낸다. 필수 항목은 적재할 때 검증하므로 없으면 적재 규칙이 깨진 것이다. */
    public BigDecimal getValue(CalcParamKey key) {
        BigDecimal value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("calc param is missing: " + key);
        }
        return value;
    }
}
