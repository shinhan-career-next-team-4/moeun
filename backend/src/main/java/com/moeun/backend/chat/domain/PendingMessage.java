package com.moeun.backend.chat.domain;

/** 답변을 기다리는 ASSISTANT 행. EXPLANATION이면 calcRunId·baselineScenario가 채워져 있다. */
public record PendingMessage(long id, long memberId, String messageType, Long calcRunId, String baselineScenario) {
}
