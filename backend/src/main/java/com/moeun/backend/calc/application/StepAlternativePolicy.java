package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;

/**
 * 대안 후보값 정책(FR-ALT-03). 월 저축액은 현재의 10%를 더하고(원 단위 반올림), 비중은 ETF를 10%p 늘린다(적금 비중 -10).
 * 값이 입력 범위(rules.yml)를 벗어나거나 현재와 같으면 후보를 내지 않는다.
 * 역산식과 변동 경고는 두지 않는다(사용자 결정, 2026-11-21).
 */
@Component
class StepAlternativePolicy implements AlternativeCandidatePolicy {

    private static final BigDecimal MONTHLY_RATE = new BigDecimal("1.10");
    private static final int RATIO_STEP_POINTS = 10;

    private final ConditionValidator validator;

    StepAlternativePolicy(ConditionValidator validator) {
        this.validator = validator;
    }

    @Override
    public Optional<Long> monthlySavingAmount(CalcInput current) {
        long raised = BigDecimal.valueOf(current.monthlySavingAmount()).multiply(MONTHLY_RATE)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return Optional.of(raised).filter(value -> value != current.monthlySavingAmount())
                .filter(value -> isAllowed(new ConditionOverride(value, null, null, null, null, null)));
    }

    @Override
    public Optional<Integer> savingsRatioPct(CalcInput current) {
        int moved = current.savingsRatioPct() - RATIO_STEP_POINTS;
        return Optional.of(moved)
                .filter(value -> isAllowed(new ConditionOverride(null, null, value, null, null, null)));
    }

    private boolean isAllowed(ConditionOverride changes) {
        return validator.validate(ChangePath.ALTERNATIVE, changes).isEmpty();
    }
}
