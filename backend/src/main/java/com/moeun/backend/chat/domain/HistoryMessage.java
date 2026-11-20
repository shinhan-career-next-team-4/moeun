package com.moeun.backend.chat.domain;

/** ai-server에 보낼 이전 대화 한 줄. role은 소문자(user, assistant)다. */
public record HistoryMessage(String role, String content) {
}
