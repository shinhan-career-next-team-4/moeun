package com.moeun.backend.plan;

/** 조건을 바꾸는 경로. 경로별로 바꿀 수 있는 항목과 개수는 rules.yml의 change-paths(CP-07). */
public enum ChangePath {
    INPUT_EDIT,
    ALTERNATIVE,
    COMPARE,
    CHAT
}
