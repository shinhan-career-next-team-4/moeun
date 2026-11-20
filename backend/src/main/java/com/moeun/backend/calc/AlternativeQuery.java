package com.moeun.backend.calc;

/** 대안 값 산출의 공개 API(FR-ALT-01·03). 계산은 모두 calc가 하고 chat은 부르기만 한다(NFR-INT-01). 기록을 남기지 않는다. */
public interface AlternativeQuery {

    /**
     * 내 현재 계획이 기준 시나리오로 목표에 못 미치면 월 저축액·기한·비중을 하나씩 바꾼 대안을 만든다.
     *
     * @param desiredTargetPeriodMonths '기한을 늘리면'의 원하는 기간(개월). null이면 그 카드는 빈 카드(D-039)
     * @throws com.moeun.backend.plan.ConditionViolationException 기간이 허용 범위 밖(422 VALIDATION_FAILED)
     * @throws com.moeun.backend.plan.NoPlanException 현재 계획 없음(409 NO_PLAN)
     */
    AlternativeView find(long memberId, Integer desiredTargetPeriodMonths);
}
