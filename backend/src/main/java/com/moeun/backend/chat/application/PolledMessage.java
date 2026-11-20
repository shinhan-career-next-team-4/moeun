package com.moeun.backend.chat.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.moeun.backend.rag.Citation;

/** 화면에 내보내는 메시지 한 건. PENDING은 messageType·content가 null이다. */
public record PolledMessage(long id, String role, String messageType, String status, String content,
        List<Citation> citations, OffsetDateTime createdAt) {
}
