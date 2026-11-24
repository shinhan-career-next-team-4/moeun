package com.moeun.backend.chat.domain;

/** 메시지 상태(schema.sql ck_cm_status). PENDING에서만 COMPLETED·FAILED로 바뀌고 되돌아가지 않는다. */
public enum MessageStatus {
    PENDING,
    COMPLETED,
    FAILED
}
