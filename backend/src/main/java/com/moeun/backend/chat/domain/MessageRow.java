package com.moeun.backend.chat.domain;

import java.time.OffsetDateTime;

/**
 * 대화 복원·폴링이 읽는 chat_message 한 행. 화면에 내보내지 않는 열은 읽지 않는다.
 *
 * @param calcRunId 결과 설명의 ETF 포함 여부를 구할 때만 쓴다(#305). 화면에 그대로 내보내지 않는다
 */
public record MessageRow(long id, String role, String messageType, String status, String content,
        Long calcRunId, OffsetDateTime createdAt) {
}
