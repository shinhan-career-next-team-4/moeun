package com.moeun.backend.refdata;

import java.util.Objects;

/**
 * 계산 한 번에 함께 쓰는 기준 데이터 활성 버전 3개. calc_run에 기록하는 버전 id의 출처다(NFR-INT-02).
 *
 * @param savingsRate 적금 평균금리 버전
 * @param sp500 S&P500 월별 지수 버전
 * @param calcParam 계산 파라미터 버전
 */
public record ActiveDataVersions(DataVersionInfo savingsRate, DataVersionInfo sp500, DataVersionInfo calcParam) {

    public ActiveDataVersions {
        Objects.requireNonNull(savingsRate, "savingsRate");
        Objects.requireNonNull(sp500, "sp500");
        Objects.requireNonNull(calcParam, "calcParam");
    }
}
