package com.moeun.backend.chat.application;

/** F-1 202 응답 본문. 두 id만 담는다(계약 C12b-4). */
public record AcceptedQuestion(long userMessageId, long assistantMessageId) {
}
