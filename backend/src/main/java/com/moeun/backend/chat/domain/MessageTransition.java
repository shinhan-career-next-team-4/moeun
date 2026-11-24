package com.moeun.backend.chat.domain;

/**
 * 메시지 한 행의 상태 전이 결과. 저장소는 행이 아직 {@code from} 상태일 때만 바꾸고(조건부 UPDATE) 바뀐 행 수를 돌려준다.
 * 0행이면 그 사이 삭제·정리·완료된 것이라 무시한다. 바꾸는 열(종류·내용·계산 기록·기준 시나리오·바꾼 조건)은 모두 여기서 정한다.
 */
public record MessageTransition(long id, long memberId, MessageStatus from, MessageStatus status, MessageType type,
        String content, Long calcRunId, String baselineScenario, String conditionChangesJson) {
}
