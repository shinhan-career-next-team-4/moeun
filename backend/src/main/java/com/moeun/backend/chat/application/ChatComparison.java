package com.moeun.backend.chat.application;

import java.util.List;

import com.moeun.backend.calc.ChatComparisonView;

/**
 * F-4 조회 결과.
 *
 * @param messageId 조회한 RECALC_RESULT 메시지 id
 * @param applicable 지금 이 비교를 적용할 수 있는지(D-041, D-068)
 * @param changedKeys 저장된 condition_changes의 키 목록(camelCase). 순서는 보장하지 않는다
 * @param view 그 메시지의 calc_run(CHAT) 기준으로 다시 계산한 비교
 */
public record ChatComparison(long messageId, boolean applicable, List<String> changedKeys, ChatComparisonView view) {
}
