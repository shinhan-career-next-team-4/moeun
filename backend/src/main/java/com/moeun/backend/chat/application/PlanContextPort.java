package com.moeun.backend.chat.application;

import java.util.Optional;

import com.moeun.backend.calc.Scenario;

/**
 * 리스너가 ai-server를 부르기 직전에 planContext를 얻는 chat 내부 포트(사람 결정 U-1).
 * 구현은 calc.PlanContextProvider에 잇는 {@link PlanContextAdapter}다(#128).
 */
public interface PlanContextPort {

    /** QUESTION: 현재 계획 기준. 계획이 없으면 empty. */
    Optional<PlanContextSnapshot> findForCurrentPlan(long memberId);

    /** EXPLAIN: 지정한 PLAN 기록과 F-3 때 저장한 기준 시나리오 기준. 기록이 없으면 empty. */
    Optional<PlanContextSnapshot> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario);
}
