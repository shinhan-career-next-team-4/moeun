package com.moeun.backend.calc;

/**
 * 설명 요청(F-3) 때 확인한 내 PLAN 기록 하나(계약 C1a-10). 재계산 없이 만든다.
 *
 * @param calcRunId calc_run(PLAN) id
 * @param planVersionId 그 기록이 계산한 계획 버전
 * @param baselineScenario 조회 시점의 계획 기준 시나리오. chat이 {@code chat_message.baseline_scenario}에 저장한다
 */
public record PlanRunRef(long calcRunId, long planVersionId, Scenario baselineScenario) {
}
