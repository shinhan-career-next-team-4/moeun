package com.moeun.backend.config;

/** 공통 처리기가 직접 싣는 오류 코드(계약 C12a-5-4). 모듈별 코드는 각 모듈의 핸들러가 문자열로 싣는다. */
public enum ProblemCode {
    MALFORMED_REQUEST,
    INTERNAL_ERROR
}
