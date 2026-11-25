package com.moeun.backend.chat.domain;

import java.util.List;

/**
 * 대화 조건 누적의 변경 목록 저장소(chat_condition_change, #418). 모든 메서드가 memberId 조건을 받는다.
 * 목록은 그 질문을 접수한 계획 버전에 속한다. 계획이 바뀌면 이전 버전의 목록은 읽히지 않는다.
 */
public interface ConditionChangeRepository {

    /** 그 계획 버전의 목록을 바꾼 순서대로. */
    List<ConditionChange> findAll(long memberId, long planVersionId);

    /** 한 RECALC_RESULT가 계산한 변경을 순서대로 덧붙인다. */
    void append(long memberId, long messageId, long planVersionId, List<ConditionChange> changes);

    /** 그 회원의 목록을 모두 지운다(적용·대화 세션 종료). */
    int deleteAllOf(long memberId);
}
