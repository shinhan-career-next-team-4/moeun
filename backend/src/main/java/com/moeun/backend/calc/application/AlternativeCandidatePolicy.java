package com.moeun.backend.calc.application;

import java.util.Optional;

import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.plan.PlanView;

/**
 * 대안 후보값을 정하는 정책(FR-ALT-03). 월 저축액과 적금 비중을 얼마로 바꿔 보일지는 아직 확정되지 않았다(Q-089·Q-103).
 * 확정 전에는 역산식과 경고 기준을 정하지 않으므로 {@link UnconfirmedAlternativePolicy}가 후보 없음을 돌려준다.
 * 기한은 사용자가 입력한 기간이라 정책이 정하지 않는다.
 * <p>구현은 rules.yml 범위 안이고 현재 계획의 값과 다른 값만 돌려줘야 한다. 이 호출부는 후보값을 다시 검증하지 않는다.
 */
public interface AlternativeCandidatePolicy {

    /**
     * @param plan 현재 계획
     * @param current 현재 계획의 계산 결과(미달인 계획만 넘어온다)
     * @return 바꿀 월 저축액(원). 정하지 않으면 빈 값
     */
    Optional<Long> monthlySavingAmount(PlanView plan, CalcResult current);

    /**
     * @param plan 현재 계획
     * @param current 현재 계획의 계산 결과(미달인 계획만 넘어온다)
     * @return 바꿀 적금 비중(%). 정하지 않으면 빈 값
     */
    Optional<Integer> savingsRatioPct(PlanView plan, CalcResult current);
}
