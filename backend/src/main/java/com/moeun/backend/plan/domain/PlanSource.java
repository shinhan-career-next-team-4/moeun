package com.moeun.backend.plan.domain;

import com.moeun.backend.plan.ChangePath;

/** 버전을 만든 경로(schema.sql ck_pv_source). 되돌리기로 복사한 버전은 원본의 경로를 그대로 쓴다. */
public enum PlanSource {
    INPUT,
    ALTERNATIVE,
    COMPARE,
    CHAT;

    /** 적용(대안·비교·대화)으로 만드는 버전의 경로. 입력 수정은 저장 경로가 따로 있다(계약 C12b-13). */
    public static PlanSource appliedBy(ChangePath path) {
        return switch (path) {
            case ALTERNATIVE -> ALTERNATIVE;
            case COMPARE -> COMPARE;
            case CHAT -> CHAT;
            case INPUT_EDIT -> throw new IllegalArgumentException("적용할 수 없는 경로입니다: " + path);
        };
    }
}
