package com.moeun.backend.refdata;

/** 기준 데이터 종류. 값은 schema.sql의 ck_data_kind 목록과 같아야 한다. */
public enum DataKind {
    /** 은행연합회 전월 평균금리(FR-DATA-01). */
    SAVINGS_RATE,
    /** S&P500 월별 과거 데이터(FR-DATA-02). */
    SP500,
    /** 세율·기본 비중·3구간 산정 기준 같은 계산 파라미터(CP-03). */
    CALC_PARAM
}
