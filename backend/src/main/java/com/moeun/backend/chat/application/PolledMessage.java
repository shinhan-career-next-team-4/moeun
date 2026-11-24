package com.moeun.backend.chat.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moeun.backend.rag.Citation;

/**
 * 화면에 내보내는 메시지 한 건. PENDING은 messageType·content가 null이다.
 *
 * @param etfIncluded 완료된 결과 설명(EXPLANATION)에만 있다. 그 설명의 계산에 ETF가 들어갔는지(#305). 나머지는 키가 없다
 */
public record PolledMessage(long id, String role, String messageType, String status, String content,
        List<Citation> citations, OffsetDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean etfIncluded) {
}
