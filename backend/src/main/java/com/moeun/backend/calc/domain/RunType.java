package com.moeun.backend.calc.domain;

/** 계산 기록 종류. 값은 schema.sql의 ck_cr_run_type 목록과 같아야 한다. */
public enum RunType {
    /** 내 계획 결과(결과 화면, planContext). */
    PLAN,
    /** 대화 재계산(/internal/calc). */
    CHAT
}
