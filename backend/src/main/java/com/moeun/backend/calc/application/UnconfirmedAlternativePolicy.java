package com.moeun.backend.calc.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.plan.PlanView;

/**
 * 후보값 정책이 확정되기 전의 기본 구현. 월 저축액·비중 후보를 내지 않아 그 카드는 빈 카드로 나간다.
 * Q-089·Q-103이 확정되면 이 클래스를 확정 정책으로 바꾼다(역산식과 경고 기준은 그때 정한다).
 */
@Component
class UnconfirmedAlternativePolicy implements AlternativeCandidatePolicy {

    @Override
    public Optional<Long> monthlySavingAmount(PlanView plan, CalcResult current) {
        return Optional.empty();
    }

    @Override
    public Optional<Integer> savingsRatioPct(PlanView plan, CalcResult current) {
        return Optional.empty();
    }
}
