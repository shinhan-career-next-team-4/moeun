package com.moeun.backend.plan;

/** 목표 종류. 선언 순서가 화면 순서다(FR-INP-01). 기본 금액·기간은 rules.yml의 goal-defaults. */
public enum GoalType {
    JEONSE,
    WEDDING,
    INDEPENDENCE,
    TRAVEL,
    CUSTOM
}
