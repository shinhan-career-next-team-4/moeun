package com.moeun.backend.calc;

import java.util.Objects;

/**
 * 계산에 쓴 기준 데이터 버전(NFR-INT-02). calc_run에 기록하는 값과 같다.
 * 같은 입력, 같은 버전, 같은 engineVersion이면 결과가 같아야 한다.
 *
 * @param savingsRateVersionId 적금 평균금리 버전. null = 기본금리를 쓰지 않음
 * @param sp500VersionId S&P500 데이터 버전. null = ETF 비중 0%
 * @param calcParamVersionId 세율 등 계산 파라미터 버전
 * @param engineVersion 계산 엔진 버전
 */
public record AppliedDataVersions(
        Long savingsRateVersionId,
        Long sp500VersionId,
        long calcParamVersionId,
        String engineVersion) {

    public AppliedDataVersions {
        Objects.requireNonNull(engineVersion, "engineVersion");
    }
}
