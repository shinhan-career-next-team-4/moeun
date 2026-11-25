package com.moeun.backend.refdata;

/** 기준 데이터 종류. 값은 schema.sql의 ck_data_kind 목록과 같아야 한다. */
public enum DataKind {
    /** 은행연합회 전월 평균금리(FR-DATA-01). */
    SAVINGS_RATE,
    /** ETF 종목별 월말 종가(FR-DATA-02, #138). 버전마다 종목 코드가 있다. S&P500도 종목 하나다. */
    ETF_PRICE,
    /** 세율·기본 비중·3구간 산정 기준 같은 계산 파라미터(CP-03). */
    CALC_PARAM
}
