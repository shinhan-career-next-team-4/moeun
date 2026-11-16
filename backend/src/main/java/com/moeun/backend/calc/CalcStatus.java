package com.moeun.backend.calc;

/** 계산 결과의 종류. 화면이 요약·3구간·그래프를 보여줄지 정하는 기준이다. */
public enum CalcStatus {
    /** 계산을 수행했다. */
    CALCULATED,
    /** 보유 자산이 목표 금액 이상이라 계산하지 않았다(FR-RES-01, 경계는 보유 ≥ 목표). */
    ALREADY_EXCEEDED
}
