package com.moeun.backend.calc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.LongPredicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.AlternativeView.Alternative;
import com.moeun.backend.calc.application.CompareConditionsUseCase.ComputedComparison;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.ConditionViolationException;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.NoPlanException;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 대안 값 산출(FR-ALT-01·03). 기준 시나리오로 목표에 못 미치는 계획만 대안을 만들고, 대안마다 조건 하나만 바꿔
 * {@link CompareConditionsUseCase#computeComparison}(#32)으로 계산한다. 결과는 저장하지 않는다.
 * <p>대안 값은 기준 시나리오가 목표를 달성하는 값을 찾아 추천한다(#343). 기한은 사용자가 정해 보내면 그 값을 쓴다.
 */
@Service
class AlternativeFinder implements AlternativeQuery {

    /** 월 저축액 추천은 만원 단위로 올린다. */
    private static final long MONTHLY_UNIT = 10_000L;
    /** 입력 규칙(rules.yml)의 상한. 이 안에서 못 찾으면 달성이 어렵다고 본다. */
    private static final long MAX_MONTHLY_AMOUNT = 10_000_000L;
    private static final long MAX_PERIOD_MONTHS = 360L;
    private static final int RATIO_STEP = 10;

    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;
    private final ConditionResolver conditionResolver;
    private final CalcEngine calcEngine;
    private final CompareConditionsUseCase compareConditions;
    private final ConditionValidator conditionValidator;

    AlternativeFinder(PlanSaveService planSaveService, ReferenceDataService referenceDataService,
            ConditionResolver conditionResolver, CalcEngine calcEngine, CompareConditionsUseCase compareConditions,
            ConditionValidator conditionValidator) {
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
        this.conditionResolver = conditionResolver;
        this.calcEngine = calcEngine;
        this.compareConditions = compareConditions;
        this.conditionValidator = conditionValidator;
    }

    @Override
    @Transactional(readOnly = true)
    public AlternativeView find(long memberId, Integer desiredTargetPeriodMonths) {
        validatePeriod(desiredTargetPeriodMonths);
        PlanView plan = planSaveService.findCurrent(memberId).orElseThrow(NoPlanException::new);
        CalcParams params = referenceDataService.findActiveCalcParams()
                .orElseThrow(() -> new IllegalStateException("active calc params are not loaded"));
        EtfSeries etf = referenceDataService.findActiveEtfSeries(EtfProductSelector.productCodeOf(plan)).orElse(null);
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElse(null);

        ConditionResolver.ResolvedConditions resolved = conditionResolver.resolve(plan, defaultRate, params);
        CalcInput input = resolved.input();
        CalcResult current = calcEngine.calculate(resolved, params, etf);
        if (isAchieved(current)) {
            return AlternativeView.notNeeded();
        }

        List<Alternative> alternatives = new ArrayList<>();
        for (AlternativeKey key : AlternativeKey.values()) {
            alternatives.add(alternativeFor(key, plan, input, desiredTargetPeriodMonths, params, etf, defaultRate));
        }
        return new AlternativeView(true, alternatives);
    }

    /** 기한을 늘리는 입력의 범위만 본다(CP-02). 값이 없으면 검사할 것이 없다. */
    private void validatePeriod(Integer months) {
        if (months == null) {
            return;
        }
        List<FieldError> errors = conditionValidator.validate(ChangePath.ALTERNATIVE,
                new ConditionOverride(null, months, null, null, null, null));
        if (!errors.isEmpty()) {
            throw new ConditionViolationException(errors);
        }
    }

    /** 이미 목표를 넘었거나(계산 안 함) 기준 시나리오 판정이 달성이면 대안이 필요 없다. */
    private static boolean isAchieved(CalcResult current) {
        return current.status() == CalcStatus.ALREADY_EXCEEDED
                || current.findBaselineResult().map(ScenarioResult::isAchieved).orElse(false);
    }

    /**
     * 대안 카드 하나. 사용자가 기간을 직접 정해 보냈으면 그 값을 쓰고, 아니면 목표를 달성하는 값을 찾는다(#343).
     * 허용 범위 안에서 찾지 못하면 '달성이 어려움' 카드다.
     */
    private Alternative alternativeFor(AlternativeKey key, PlanView plan, CalcInput input, Integer desiredMonths,
            CalcParams params, EtfSeries etf, SavingsAvgRateData defaultRate) {
        if (key == AlternativeKey.TARGET_PERIOD_MONTHS && desiredMonths != null) {
            return compute(key, desiredMonths, plan, params, etf, defaultRate);
        }
        LongPredicate achieves = value -> achieves(plan, onlyChanged(key, value), params, etf, defaultRate);
        OptionalLong found = seek(key, input, achieves);
        if (found.isEmpty()) {
            return Alternative.unreachable(key);
        }
        return compute(key, found.getAsLong(), plan, params, etf, defaultRate);
    }

    private OptionalLong seek(AlternativeKey key, CalcInput input, LongPredicate achieves) {
        return switch (key) {
            case MONTHLY_SAVING_AMOUNT -> {
                long firstUnit = input.monthlySavingAmount() / MONTHLY_UNIT + 1;
                OptionalLong unit = GoalSeeker.smallestAchieving(firstUnit, MAX_MONTHLY_AMOUNT / MONTHLY_UNIT,
                        candidate -> achieves.test(candidate * MONTHLY_UNIT));
                yield unit.isPresent() ? OptionalLong.of(unit.getAsLong() * MONTHLY_UNIT) : OptionalLong.empty();
            }
            case TARGET_PERIOD_MONTHS -> GoalSeeker.smallestAchieving(input.targetPeriodMonths() + 1L,
                    MAX_PERIOD_MONTHS, achieves);
            case SAVINGS_RATIO_PCT -> GoalSeeker.firstAchieving(ratioCandidates(input.savingsRatioPct()), achieves);
        };
    }

    /** 현재 적금 비중보다 낮은(ETF를 늘린) 10%p 단위 후보를 현재에서 가까운 순서로 준다. 0이 ETF 100%다. */
    private static long[] ratioCandidates(int currentRatioPct) {
        int first = (currentRatioPct - 1) / RATIO_STEP * RATIO_STEP;
        int count = first / RATIO_STEP + 1;
        if (currentRatioPct <= 0) {
            return new long[0];
        }
        long[] candidates = new long[count];
        for (int i = 0; i < count; i++) {
            candidates[i] = first - (long) i * RATIO_STEP;
        }
        return candidates;
    }

    /** 바꾼 조건 하나로 계산했을 때 기준 시나리오가 목표를 달성하는지. */
    private boolean achieves(PlanView plan, ConditionOverride changes, CalcParams params, EtfSeries etf,
            SavingsAvgRateData defaultRate) {
        if (!conditionValidator.validate(ChangePath.ALTERNATIVE, changes).isEmpty()) {
            return false;
        }
        ConditionResolver.ResolvedConditions changed = conditionResolver.resolveChanged(plan, changes, defaultRate,
                params);
        return isAchieved(calcEngine.calculate(changed, params, etf));
    }

    private Alternative compute(AlternativeKey key, long value, PlanView plan, CalcParams params, EtfSeries etf,
            SavingsAvgRateData defaultRate) {
        ComputedComparison computed = compareConditions.computeComparison(plan, onlyChanged(key, value), params, etf,
                etf, defaultRate);
        return new Alternative(key, value, computed.comparison(), computed.display());
    }

    /** 한 조건만 담은 바꾼 조건. 나머지는 null이라 현재 계획 그대로다(FR-ALT-03). */
    private static ConditionOverride onlyChanged(AlternativeKey key, long value) {
        return switch (key) {
            case MONTHLY_SAVING_AMOUNT -> new ConditionOverride(value, null, null, null, null, null);
            case TARGET_PERIOD_MONTHS -> new ConditionOverride(null, (int) value, null, null, null, null);
            case SAVINGS_RATIO_PCT -> new ConditionOverride(null, null, (int) value, null, null, null);
        };
    }
}
