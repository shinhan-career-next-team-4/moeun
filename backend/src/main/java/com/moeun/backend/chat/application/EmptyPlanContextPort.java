package com.moeun.backend.chat.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.Scenario;

/**
 * 임시 구현: 항상 empty. #78(calc.PlanContextProvider) 뒤에 그 모듈에 잇는 어댑터로 바꾼다(사람 결정 U-1).
 * 그때까지는 계획이 있어도 "계획 없음"처럼 답한다.
 */
@Component
class EmptyPlanContextPort implements PlanContextPort {

    @Override
    public Optional<PlanContextSnapshot> findForCurrentPlan(long memberId) {
        return Optional.empty();
    }

    @Override
    public Optional<PlanContextSnapshot> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario) {
        return Optional.empty();
    }
}
