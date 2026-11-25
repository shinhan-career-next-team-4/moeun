package com.moeun.backend.chat.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.chat.domain.ConditionChangeRepository;

/**
 * F-6 대화 조건 누적 비우기(#418, D-103). 대화 세션이 끝나면(대화창 닫기) 그 회원의 변경 목록을 지운다.
 * 다음 질문은 내 계획을 기준으로 계산한다. 이미 비어 있어도 같다.
 */
@Service
public class ChatConditionChangeService {

    private final ConditionChangeRepository conditionChanges;

    ChatConditionChangeService(ConditionChangeRepository conditionChanges) {
        this.conditionChanges = conditionChanges;
    }

    @Transactional
    public void clear(long memberId) {
        conditionChanges.deleteAllOf(memberId);
    }
}
