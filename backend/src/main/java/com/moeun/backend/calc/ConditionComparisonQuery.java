package com.moeun.backend.calc;

import com.moeun.backend.plan.ConditionOverride;

/**
 * 조건 변경 비교(FR-CMP-02, #64)를 위한 calc의 공개 API. 현재 계획에 바꾼 조건을 얹어 비교만 계산하고 아무것도 기록하지
 * 않는다(calc_run도, 계획도 그대로). 대화 재계산({@code /internal/calc})과 같은 비교 계산을 쓰므로 같은 입력이면 같은 숫자다.
 */
public interface ConditionComparisonQuery {

    /**
     * 그 회원의 현재 계획을 기준으로 바꾼 조건만 얹어 current·changed를 계산한다. 범위 검사는 하지 않는다(부르는 쪽이
     * 먼저 {@code ConditionValidator}로 검증한다).
     *
     * @param changes 검증을 통과한 바꾼 조건(하나 이상)
     * @throws CalcRejectedException 계획 없음(409 NO_PLAN), 바꾼 조건으로도 이미 목표 초과(409 ALREADY_EXCEEDED)
     */
    ChatComparisonView compareWithCurrentPlan(long memberId, ConditionOverride changes);
}
