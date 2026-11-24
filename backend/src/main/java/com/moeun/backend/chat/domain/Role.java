package com.moeun.backend.chat.domain;

/** 메시지를 쓴 쪽(schema.sql ck_cm_role). USER면 QUESTION이고 그 밖은 ASSISTANT다(ck_cm_role_type). */
public enum Role {
    USER,
    ASSISTANT
}
