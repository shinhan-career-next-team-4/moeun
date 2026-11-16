package com.moeun.backend.refdata.application;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.moeun.backend.refdata.CalcParamKey;

/** 계산 파라미터 한 벌이 계산에 쓸 수 있는 상태인지 버전을 켜기 전에 확인한다. */
final class CalcParamLoader {

    private CalcParamLoader() {
    }

    /** 필수 항목이 빠지면 계산 도중에야 드러나므로 적재할 때 막는다(DB 트리거를 대신한다, moved-to-code). */
    static void validateRequiredKeys(Map<CalcParamKey, BigDecimal> values) {
        List<CalcParamKey> missingKeys = Arrays.stream(CalcParamKey.values())
                .filter(CalcParamKey::isRequired)
                .filter(key -> !values.containsKey(key))
                .toList();
        if (!missingKeys.isEmpty()) {
            throw new ReferenceDataLoadException("required param keys are missing: " + missingKeys);
        }
        BigDecimal lowPercentile = values.get(CalcParamKey.SCENARIO_LOW_PERCENTILE);
        BigDecimal highPercentile = values.get(CalcParamKey.SCENARIO_HIGH_PERCENTILE);
        if (lowPercentile.compareTo(highPercentile) >= 0) {
            throw new ReferenceDataLoadException("SCENARIO_LOW_PERCENTILE must be below SCENARIO_HIGH_PERCENTILE");
        }
    }
}
