package com.moeun.backend.plan;

/** 한 회원의 계획을 모두 지우는 공개 서비스(데모 초기화). 계정은 남긴다. */
public interface PlanEraser {

    /**
     * 현재 계획 포인터(plan)를 먼저, 그다음 계획 버전(plan_version)을 지운다. 계산 기록(calc_run)은 버전과 함께 지워진다.
     * 대화(chat_message)가 calc_run을 참조하므로 부르는 쪽이 대화를 먼저 지워야 한다. 두 번 불러도 결과가 같다.
     */
    void deleteAllOf(long memberId);
}
