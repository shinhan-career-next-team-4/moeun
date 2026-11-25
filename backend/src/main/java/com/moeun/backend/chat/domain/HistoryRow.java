package com.moeun.backend.chat.domain;

import java.util.Locale;

/**
 * ai-server에 보낼 이전 대화 한 행. 계산 결과의 금액은 기록으로 넘기지 않는다(#418): 다시 계산한 비교·설명의 글은 그때의
 * 금액을 담고 있어, 지금 상태의 숫자는 계산 엔진에서 새로 받게 하고 기록에는 금액 없는 안내 문장을 둔다.
 */
public record HistoryRow(Role role, MessageType type, String content) {

    /** @param resultOmitted 계산 결과 글 대신 넣을 안내 문장(문구표) */
    public HistoryMessage toHistory(String resultOmitted) {
        boolean result = type == MessageType.RECALC_RESULT || type == MessageType.EXPLANATION;
        return new HistoryMessage(role.name().toLowerCase(Locale.ROOT), result ? resultOmitted : content);
    }
}
